use std::{
    cmp::PartialEq,
    collections::{HashMap, hash_map::Entry},
    fs,
    fs::{DirEntry, FileType, create_dir, create_dir_all, read_dir, read_link},
    io,
    os::unix::fs::{FileTypeExt, symlink},
    path::{Path, PathBuf},
};

use anyhow::{Context, Result, bail};
use extattr::{lgetxattr, lsetxattr, Flags as XattrFlags};
use rustix::{
    fs::{
        Gid, MetadataExt, Mode, MountFlags, MountPropagationFlags, Uid, UnmountFlags, bind_mount,
        chmod, chown, mount, move_mount, unmount,
    },
    mount::mount_change,
};

use crate::{
    defs::{AP_MAGIC_MOUNT_SOURCE, AP_OVERLAY_SOURCE, DISABLE_FILE_NAME, MODULE_DIR, SKIP_MOUNT_FILE_NAME},
    magic_mount::NodeFileType::{Directory, RegularFile, Symlink, Whiteout},
    restorecon::{lgetfilecon, lsetfilecon},
    utils::{ensure_dir_exists, get_work_dir},
};

const REPLACE_DIR_XATTR: &str = "trusted.overlay.opaque";

#[derive(PartialEq, Eq, Hash, Clone, Debug)]
enum NodeFileType {
    RegularFile,
    Directory,
    Symlink,
    Whiteout,
}

impl NodeFileType {
    fn from_file_type(file_type: FileType) -> Option<Self> {
        if file_type.is_file() {
            Some(RegularFile)
        } else if file_type.is_dir() {
            Some(Directory)
        } else if file_type.is_symlink() {
            Some(Symlink)
        } else {
            None
        }
    }
}

#[derive(Debug, Clone)]
struct Node {
    name: String,
    file_type: NodeFileType,
    children: HashMap<String, Node>,
    // the module that owned this node
    module_path: Option<PathBuf>,
    replace: bool,
    skip: bool,
}

impl Node {
    fn collect_module_files<T: AsRef<Path>>(&mut self, module_dir: T) -> Result<bool> {
        let dir = module_dir.as_ref();
        let mut has_file = false;
        for entry in dir.read_dir()?.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();

            let node = match self.children.entry(name.clone()) {
                Entry::Occupied(o) => Some(o.into_mut()),
                Entry::Vacant(v) => Self::new_module(&name, &entry).map(|it| v.insert(it)),
            };

            if let Some(node) = node {
                has_file |= if node.file_type == Directory {
                    node.collect_module_files(dir.join(&node.name))? || node.replace
                } else {
                    true
                }
            }
        }

        Ok(has_file)
    }

    fn new_root<T: ToString>(name: T) -> Self {
        Node {
            name: name.to_string(),
            file_type: Directory,
            children: Default::default(),
            module_path: None,
            replace: false,
            skip: false,
        }
    }

    fn new_module<T: ToString>(name: T, entry: &DirEntry) -> Option<Self> {
        if let Ok(metadata) = entry.metadata() {
            let path = entry.path();
            let file_type = if metadata.file_type().is_char_device() && metadata.rdev() == 0 {
                Some(Whiteout)
            } else {
                NodeFileType::from_file_type(metadata.file_type())
            };
            if let Some(file_type) = file_type {
                let mut replace = false;
                if file_type == Directory {
                    if let Ok(v) = lgetxattr(&path, REPLACE_DIR_XATTR) {
                        if String::from_utf8_lossy(&v) == "y" {
                            replace = true;
                        }
                    }
                }
                return Some(Node {
                    name: name.to_string(),
                    file_type,
                    children: Default::default(),
                    module_path: Some(path),
                    replace,
                    skip: false,
                });
            }
        }

        None
    }
}

fn should_mount_partition(partition: &str, require_symlink: bool) -> bool {
    let path_of_root = Path::new("/").join(partition);
    let path_of_system = Path::new("/system").join(partition);

    // Partition must exist as a directory
    if !path_of_root.is_dir() {
        log::debug!("partition /{partition} does not exist or is not a directory");
        return false;
    }

    // Special handling for system partition - always mount if exists
    if partition == "system" {
        return true;
    }

    if require_symlink {
        if !path_of_system.is_symlink() {
            log::debug!(
                "partition /{partition} is not a symlink from /system/{partition}, skipping"
            );
            return false;
        }
    } else {
        // For non-required symlink partitions, skip if /system/xxx exists as a symlink
        // This means it's already part of system partition
        if path_of_system.is_symlink() {
            log::debug!(
                "partition /{partition} is a symlink to /system/{partition}, skipping separate mount"
            );
            return false;
        }
    }

    true
}

fn collect_module_files() -> Result<Option<Node>> {
    let mut root = Node::new_root("");
    let mut system = Node::new_root("system");
    let module_root = Path::new(MODULE_DIR);
    let mut has_file = false;
    for entry in module_root.read_dir()?.flatten() {
        if !entry.file_type()?.is_dir() {
            continue;
        }

        if entry.path().join(DISABLE_FILE_NAME).exists()
            || entry.path().join(SKIP_MOUNT_FILE_NAME).exists()
        {
            continue;
        }

        let mod_system = entry.path().join("system");
        if !mod_system.is_dir() {
            continue;
        }

        log::debug!("collecting {}", entry.path().display());

        has_file |= system.collect_module_files(&mod_system)?;
    }

    if has_file {
        let partitions = [
            ("vendor", true),
            ("system_ext", true),
            ("product", true),
            ("odm", false),
            ("oem", false),
            ("my_product", false),
            ("my_preload", false),
        ];

        // Move partition nodes from system to root before system is moved
        for (partition, require_symlink) in partitions {
            if should_mount_partition(partition, require_symlink) {
                let name = partition.to_string();
                if let Some(node) = system.children.remove(&name) {
                    root.children.insert(name, node);
                    log::debug!(
                        "partition /{partition} will be mounted separately (require_symlink={})",
                        require_symlink
                    );
                }
            }
        }

        // Now insert system partition (always requires symlink check=false)
        if should_mount_partition("system", false) {
            root.children.insert("system".to_string(), system);
        }

        Ok(Some(root))
    } else {
        Ok(None)
    }
}

fn clone_symlink<Src: AsRef<Path>, Dst: AsRef<Path>>(src: Src, dst: Dst) -> Result<()> {
    let src_symlink = read_link(src.as_ref())?;
    symlink(&src_symlink, dst.as_ref())?;
    lsetfilecon(dst.as_ref(), lgetfilecon(src.as_ref())?.as_str())?;
    log::debug!(
        "clone symlink {} -> {}({})",
        dst.as_ref().display(),
        dst.as_ref().display(),
        src_symlink.display()
    );
    Ok(())
}

fn mount_mirror<P: AsRef<Path>, WP: AsRef<Path>>(
    path: P,
    work_dir_path: WP,
    entry: &DirEntry,
) -> Result<()> {
    let path = path.as_ref().join(entry.file_name());
    let work_dir_path = work_dir_path.as_ref().join(entry.file_name());
    let file_type = entry.file_type()?;

    if file_type.is_file() {
        log::debug!(
            "mount mirror file {} -> {}",
            path.display(),
            work_dir_path.display()
        );
        fs::File::create(&work_dir_path)?;
        bind_mount(&path, &work_dir_path)?;
    } else if file_type.is_dir() {
        log::debug!(
            "mount mirror dir {} -> {}",
            path.display(),
            work_dir_path.display()
        );
        create_dir(&work_dir_path)?;
        let metadata = entry.metadata()?;
        chmod(&work_dir_path, Mode::from_raw_mode(metadata.mode()))?;
        unsafe {
            chown(
                &work_dir_path,
                Some(Uid::from_raw(metadata.uid())),
                Some(Gid::from_raw(metadata.gid())),
            )?;
        }
        lsetfilecon(&work_dir_path, lgetfilecon(&path)?.as_str())?;
        for entry in read_dir(&path)?.flatten() {
            mount_mirror(&path, &work_dir_path, &entry)?;
        }
    } else if file_type.is_symlink() {
        log::debug!(
            "create mirror symlink {} -> {}",
            path.display(),
            work_dir_path.display()
        );
        clone_symlink(&path, &work_dir_path)?;
    }

    Ok(())
}

fn do_magic_mount<P: AsRef<Path>, WP: AsRef<Path>>(
    path: P,
    work_dir_path: WP,
    current: Node,
    has_tmpfs: bool,
) -> Result<()> {
    let mut current = current;
    let path = path.as_ref().join(&current.name);
    let work_dir_path = work_dir_path.as_ref().join(&current.name);
    match current.file_type {
        RegularFile => {
            let target_path = if has_tmpfs {
                fs::File::create(&work_dir_path)?;
                &work_dir_path
            } else {
                &path
            };
            if let Some(module_path) = &current.module_path {
                log::debug!(
                    "mount module file {} -> {}",
                    module_path.display(),
                    work_dir_path.display()
                );
                bind_mount(module_path, target_path)?;
            } else {
                bail!("cannot mount root file {}!", path.display());
            }
        }
        Symlink => {
            if let Some(module_path) = &current.module_path {
                log::debug!(
                    "create module symlink {} -> {}",
                    module_path.display(),
                    work_dir_path.display()
                );
                clone_symlink(module_path, &work_dir_path)?;
            } else {
                bail!("cannot mount root symlink {}!", path.display());
            }
        }
        Directory => {
            let mut create_tmpfs = !has_tmpfs && current.replace && current.module_path.is_some();
            if !has_tmpfs && !create_tmpfs {
                for it in &mut current.children {
                    let (name, node) = it;
                    let real_path = path.join(name);
                    let need = match node.file_type {
                        Symlink => true,
                        Whiteout => real_path.exists(),
                        _ => {
                            if let Ok(metadata) = real_path.symlink_metadata() {
                                let file_type = NodeFileType::from_file_type(metadata.file_type())
                                    .unwrap_or(Whiteout);
                                file_type != node.file_type || file_type == Symlink
                            } else {
                                // real path not exists
                                true
                            }
                        }
                    };
                    if need {
                        if current.module_path.is_none() {
                            log::error!(
                                "cannot create tmpfs on {}, ignore: {name}",
                                path.display()
                            );
                            node.skip = true;
                            continue;
                        }
                        create_tmpfs = true;
                        break;
                    }
                }
            }

            let has_tmpfs = has_tmpfs || create_tmpfs;

            if has_tmpfs {
                log::debug!(
                    "creating tmpfs skeleton for {} at {}",
                    path.display(),
                    work_dir_path.display()
                );
                create_dir_all(&work_dir_path)?;
                let (metadata, path) = if path.exists() {
                    (path.metadata()?, &path)
                } else if let Some(module_path) = &current.module_path {
                    (module_path.metadata()?, module_path)
                } else {
                    bail!("cannot mount root dir {}!", path.display());
                };
                chmod(&work_dir_path, Mode::from_raw_mode(metadata.mode()))?;
                unsafe {
                    chown(
                        &work_dir_path,
                        Some(Uid::from_raw(metadata.uid())),
                        Some(Gid::from_raw(metadata.gid())),
                    )?;
                }
                lsetfilecon(&work_dir_path, lgetfilecon(path)?.as_str())?;
            }

            if create_tmpfs {
                log::debug!(
                    "creating tmpfs for {} at {}",
                    path.display(),
                    work_dir_path.display()
                );
                bind_mount(&work_dir_path, &work_dir_path).context("bind self")?;
            }

            if path.exists() && !current.replace {
                for entry in path.read_dir()?.flatten() {
                    let name = entry.file_name().to_string_lossy().to_string();
                    let result = if let Some(node) = current.children.remove(&name) {
                        if node.skip {
                            continue;
                        }
                        do_magic_mount(&path, &work_dir_path, node, has_tmpfs)
                            .with_context(|| format!("magic mount {}/{name}", path.display()))
                    } else if has_tmpfs {
                        mount_mirror(&path, &work_dir_path, &entry)
                            .with_context(|| format!("mount mirror {}/{name}", path.display()))
                    } else {
                        Ok(())
                    };

                    if let Err(e) = result {
                        if has_tmpfs {
                            return Err(e);
                        } else {
                            log::error!("mount child {}/{name} failed: {}", path.display(), e);
                        }
                    }
                }
            }

            if current.replace {
                if current.module_path.is_none() {
                    bail!(
                        "dir {} is declared as replaced but it is root!",
                        path.display()
                    );
                } else {
                    log::debug!("dir {} is replaced", path.display());
                }
            }

            for (name, node) in current.children.into_iter() {
                if node.skip {
                    continue;
                }
                if let Err(e) = do_magic_mount(&path, &work_dir_path, node, has_tmpfs)
                    .with_context(|| format!("magic mount {}/{name}", path.display()))
                {
                    if has_tmpfs {
                        return Err(e);
                    } else {
                        log::error!("mount child {}/{name} failed: {}", path.display(), e);
                    }
                }
            }

            if create_tmpfs {
                log::debug!(
                    "moving tmpfs {} -> {}",
                    work_dir_path.display(),
                    path.display()
                );
                move_mount(&work_dir_path, &path).context("move self")?;
                mount_change(&path, MountPropagationFlags::PRIVATE).context("make self private")?;
            }
        }
        Whiteout => {
            log::debug!("file {} is removed", path.display());
        }
    }

    Ok(())
}

pub fn magic_mount() -> Result<()> {
    match collect_module_files()? {
        Some(root) => {
            log::debug!("collected: {:#?}", root);
            let tmp_dir = PathBuf::from(get_work_dir());
            ensure_dir_exists(&tmp_dir)?;
            mount(
                AP_MAGIC_MOUNT_SOURCE,
                &tmp_dir,
                "tmpfs",
                MountFlags::empty(),
                "",
            )
            .context("mount tmp")?;
            mount_change(&tmp_dir, MountPropagationFlags::PRIVATE).context("make tmp private")?;
            let result = do_magic_mount("/", &tmp_dir, root, false);
            if let Err(e) = unmount(&tmp_dir, UnmountFlags::DETACH) {
                log::error!("failed to unmount tmp {}", e);
            }
            fs::remove_dir(tmp_dir).ok();
            result
        }
        _ => {
            log::info!("no modules to mount, skipping!");
            Ok(())
        }
    }
}

/// Mount a partition using OverlayFS
///
/// # Arguments
/// * `partition_name` - Name of the partition (e.g., "vendor", "product")
/// * `lowerdir` - List of lower directories (module directories)
///
/// # Returns
/// - `Ok(())` if mount succeeded
/// - `Err(...)` if mount failed
fn mount_overlay_partition(partition_name: &str, lowerdir: &[String]) -> Result<()> {
    if lowerdir.is_empty() {
        log::warn!("partition: {} lowerdir is empty", partition_name);
        return Ok(());
    }

    let partition = format!("/{}", partition_name);

    // Check if partition exists
    let partition_path = Path::new(&partition);
    if !partition_path.exists() {
        log::warn!("partition: {} does not exist", partition);
        return Ok(());
    }

    // Check if /system/{partition} is a symlink
    let system_partition = format!("/system/{}", partition_name);
    let system_partition_path = Path::new(&system_partition);
    if system_partition_path.is_symlink() {
        log::warn!("partition: {} is a symlink to /system/{}", partition, partition_name);
        return Ok(());
    }

    // Construct lowerdir configuration string
    let lowerdir_config = lowerdir.join(":");
    log::info!(
        "mount overlayfs on {}, lowerdir={}",
        partition,
        lowerdir_config
    );

    // Use traditional mount API for OverlayFS
    // This is more compatible across different kernel versions
    let data = format!("lowerdir={}", lowerdir_config);
    mount(
        AP_OVERLAY_SOURCE,
        &partition,
        "overlay",
        MountFlags::empty(),
        data,
    )?;

    log::info!("successfully mounted overlayfs on {}", partition);
    Ok(())
}

/// Mount system partition using OverlayFS
///
/// This is the main entry point for OverlayFS mode
/// It collects module files and mounts them using overlayfs instead of bind mount
///
/// # Returns
/// - `Ok(())` if mount succeeded
/// - `Err(...)` if mount failed
pub fn overlayfs_mount() -> Result<()> {
    log::info!("starting overlayfs mount");

    // Collect module system roots (e.g., /data/adb/modules/XXX/system)
    let module_roots = collect_module_system_roots()?;

    if module_roots.is_empty() {
        log::info!("no modules to mount, skipping!");
        return Ok(());
    }

    log::debug!("collected module roots: {:?}", module_roots);

    let mut failed_partitions = Vec::new();

    // Mount each partition (system, vendor, odm, etc.)
    for partition in ["system", "vendor", "odm", "product", "system_ext"] {
        let partition_path = format!("/{}", partition);

        if !Path::new(&partition_path).exists() {
            log::debug!("partition {} does not exist, skipping", partition);
            continue;
        }

        log::info!("mounting overlayfs for partition {}", partition);

        match mount_overlay_partition_recursive(&partition_path, &module_roots) {
            Ok(_) => {
                log::info!("successfully mounted overlayfs for {}", partition);
            }
            Err(e) => {
                log::warn!("failed to mount overlayfs for {}: {}, will use Magic Mount fallback", partition, e);
                failed_partitions.push(partition.to_string());
            }
        }
    }

    // For partitions where OverlayFS failed, use Magic Mount
    if !failed_partitions.is_empty() {
        log::info!("using Magic Mount fallback for partitions: {:?}", failed_partitions);
        if let Err(e) = magic_mount_for_partitions(&failed_partitions) {
            log::error!("Magic Mount fallback failed: {}", e);
            // Return error to trigger complete Magic Mount in event.rs
            return Err(e.context("Magic Mount fallback failed"));
        }
    }

    log::info!("overlayfs mount completed successfully");
    Ok(())
}

/// Collect all module system directories
///
/// Returns a list of paths like /data/adb/modules/XXX/system
fn collect_module_system_roots() -> Result<Vec<String>> {
    let mut module_roots = Vec::new();
    let module_root = Path::new(MODULE_DIR);

    for entry in module_root.read_dir()?.flatten() {
        if !entry.file_type()?.is_dir() {
            continue;
        }

        if entry.path().join(DISABLE_FILE_NAME).exists()
            || entry.path().join(SKIP_MOUNT_FILE_NAME).exists()
        {
            continue;
        }

        let mod_system = entry.path().join("system");
        if !mod_system.is_dir() {
            continue;
        }

        module_roots.push(mod_system.to_string_lossy().to_string());
        log::debug!("found module system root: {}", mod_system.display());
    }

    Ok(module_roots)
}

/// Mount OverlayFS for a partition and all its child mount points
///
/// This function:
/// 1. Reads /proc/mounts to find all child mount points under the partition
/// 2. Skips mount points that are already overlay type (system overlays)
/// 3. Mounts OverlayFS on remaining child mount points that have module files
///
/// Returns Err if any child mount with module files failed or was skipped due to overlay
fn mount_overlay_partition_recursive(partition_path: &str, module_roots: &[String]) -> Result<()> {
    log::info!("mounting overlayfs for {}", partition_path);

    // Read current mount info to find child mount points
    let mut child_mounts = Vec::new();
    let mut overlay_mounts = Vec::new();

    if let Ok(mounts) = fs::read_to_string("/proc/mounts") {
        let mut all_mounts: std::collections::HashMap<String, Vec<String>> = std::collections::HashMap::new();

        for line in mounts.lines() {
            let parts: Vec<&str> = line.split_whitespace().collect();
            if parts.len() >= 3 {
                let mount_point = parts[1];
                let fs_type = parts[2];

                // Check if this mount point is under our partition
                if mount_point.starts_with(partition_path) && mount_point != partition_path {
                    all_mounts
                        .entry(mount_point.to_string())
                        .or_insert_with(Vec::new)
                        .push(fs_type.to_string());
                }
            }
        }

        // Classify mount points
        for (mount_point, fs_types) in all_mounts {
            if fs_types.iter().any(|t| t == "overlay") {
                overlay_mounts.push(mount_point.clone());
                log::debug!("skipping existing overlay mount: {}", mount_point);
            } else {
                child_mounts.push(mount_point);
            }
        }
    }

    log::debug!("found {} child mount points under {} ({} are already overlay)",
                child_mounts.len(), partition_path, overlay_mounts.len());

    // Check if any overlay mount points have module files
    let mut has_modules_in_overlay = false;
    for overlay_mount in &overlay_mounts {
        let relative = overlay_mount.replacen(partition_path, "", 1);
        for module_root in module_roots {
            let module_path = format!("{}{}", module_root, relative);
            if Path::new(&module_path).exists() {
                log::info!("found module files in overlay mount {}, Magic Mount needed", overlay_mount);
                has_modules_in_overlay = true;
            }
        }
    }

    if has_modules_in_overlay {
        log::warn!("partition {} has module files in existing overlay mounts, Magic Mount fallback required", partition_path);
        return Err(anyhow::anyhow!("module files exist in system overlay mounts"));
    }

    // Sort child mounts (shorter paths first)
    child_mounts.sort();
    child_mounts.dedup();

    // Mount each child mount point with OverlayFS
    let mut has_failed_mounts = false;
    for child_mount in &child_mounts {
        // Skip if this path has a parent that's already an overlay
        let mut skip = false;
        for overlay_mount in &overlay_mounts {
            if child_mount.starts_with(overlay_mount) {
                log::debug!("skipping {} because parent {} is overlay", child_mount, overlay_mount);
                skip = true;
                break;
            }
        }

        if skip {
            continue;
        }

        let relative = child_mount.replacen(partition_path, "", 1);
        let stock_root = child_mount;

        log::debug!("processing child mount: {} (relative: {})", child_mount, relative);

        if let Err(e) = mount_overlay_child(child_mount, &relative, module_roots, stock_root) {
            log::warn!("failed to mount overlayfs for child {}: {}", child_mount, e);
            has_failed_mounts = true;
            // Don't try bind mount here, let the partition-level fallback handle it
        }
    }

    if has_failed_mounts {
        log::warn!("some child mounts failed for {}, partition-level Magic Mount will be used", partition_path);
        return Err(anyhow::anyhow!("overlayfs child mounts failed"));
    }

    // Now scan for module files in non-mountpoint directories
    log::debug!("scanning for module files in non-mountpoint directories");

    // Check if there are any module files in non-mountpoint directories
    let mut has_non_mountpoint_modules = false;

    for module_root in module_roots {
        if let Ok(entries) = fs::read_dir(module_root) {
            for entry in entries.flatten() {
                if let Ok(file_type) = entry.file_type() {
                    if file_type.is_dir() {
                        let dir_name = entry.file_name();
                        // Create the target path: e.g., /system/bin
                        let dir_path = format!("{}/{}", partition_path, dir_name.to_string_lossy());

                        // Skip if this is already a known mount point
                        let is_known_mount = child_mounts.iter().any(|m| *m == dir_path)
                            || overlay_mounts.iter().any(|m| *m == dir_path);

                        if !is_known_mount {
                            // Check if this directory has any files in any module
                            for module_root2 in module_roots {
                                // Check path: /data/adb/modules/YYY/system/bin
                                let check_path = format!("{}/{}", module_root2, dir_name.to_string_lossy());
                                if Path::new(&check_path).exists() {
                                    if let Ok(entries2) = fs::read_dir(&check_path) {
                                        if entries2.count() > 0 {
                                            has_non_mountpoint_modules = true;
                                            log::debug!("found module files in non-mountpoint directory: {}", dir_path);
                                            break;
                                        }
                                    }
                                }
                            }
                            if has_non_mountpoint_modules {
                                break;
                            }
                        }
                    }
                }
            }
        }
        if has_non_mountpoint_modules {
            break;
        }
    }

    if has_non_mountpoint_modules {
        log::warn!("partition {} has module files in non-mountpoint directories, Magic Mount required", partition_path);
        return Err(anyhow::anyhow!("module files exist in non-mountpoint directories"));
    }

    log::info!("completed mounting overlayfs for {}", partition_path);

    Ok(())
}

fn mount_overlay_child(
    mount_point: &str,
    relative: &str,
    module_roots: &[String],
    stock_root: &str,
) -> Result<()> {
    // Collect lower dirs from modules
    let mut lower_dirs = Vec::new();

    for module_root in module_roots {
        let module_lower_dir = format!("{}{}", module_root, relative);
        let path = Path::new(&module_lower_dir);

        if path.is_dir() {
            // Check if directory has files
            if let Ok(mut entries) = path.read_dir() {
                if entries.next().is_some() {
                    lower_dirs.push(module_lower_dir);
                }
            }
        } else if path.exists() {
            // It's a file, stock root is blocked
            log::debug!("stock root blocked by file: {}", module_lower_dir);
            return Ok(());
        }
    }

    if lower_dirs.is_empty() {
        log::debug!("no module files for {}, skipping", mount_point);
        return Ok(());
    }

    // Add stock root as the lowest layer
    lower_dirs.push(stock_root.to_string());

    log::info!(
        "mounting overlayfs on {} with {} lower layers",
        mount_point,
        lower_dirs.len()
    );

    let lowerdir_config = lower_dirs.join(":");
    let data = format!("lowerdir={}", lowerdir_config);

    mount(
        AP_OVERLAY_SOURCE,
        mount_point,
        "overlay",
        MountFlags::empty(),
        &data,
    ).context("mount overlayfs")?;

    log::info!("successfully mounted overlayfs on {}", mount_point);

    Ok(())
}

fn magic_mount_for_partitions(partitions: &[String]) -> Result<()> {
    log::info!("starting Magic Mount for partitions: {:?}", partitions);

    // Collect module files using the existing function
    let root = match collect_module_files()? {
        Some(r) => r,
        None => {
            log::info!("no modules to mount, skipping!");
            return Ok(());
        }
    };

    log::debug!("collected modules for Magic Mount fallback: {:#?}", root);

    // Create temporary work directory
    let tmp_dir = PathBuf::from(get_work_dir());
    ensure_dir_exists(&tmp_dir)?;
    mount(
        AP_MAGIC_MOUNT_SOURCE,
        &tmp_dir,
        "tmpfs",
        MountFlags::empty(),
        "",
    )
    .context("mount tmp for magic mount fallback")?;
    mount_change(&tmp_dir, MountPropagationFlags::PRIVATE).context("make tmp private")?;

    // Mount only the specified partitions
    for partition_name in partitions {
        if let Some(partition_node) = root.children.get(partition_name) {
            log::info!("mounting partition {} with Magic Mount", partition_name);

            // Clone the node since do_magic_mount takes ownership
            let partition_node_clone = partition_node.clone();
            // Pass "/" as base path, do_magic_mount will join with partition_node.name
            if let Err(e) = do_magic_mount("/", &tmp_dir, partition_node_clone, false) {
                log::warn!("Magic Mount failed for {}: {}", partition_name, e);
            }
        }
    }

    // Cleanup
    if let Err(e) = unmount(&tmp_dir, UnmountFlags::DETACH) {
        log::error!("failed to unmount tmp: {}", e);
    }
    fs::remove_dir(tmp_dir).ok();

    log::info!("Magic Mount fallback completed");
    Ok(())
}
