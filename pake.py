import os
import shutil

OLD_PACKAGE = "me.bmax.apatch"
NEW_PACKAGE = "me.bmax.apatch"

OLD_PATH = OLD_PACKAGE.replace(".", os.sep)
NEW_PATH = NEW_PACKAGE.replace(".", os.sep)

OLD_JNI = OLD_PACKAGE.replace(".", "_")
NEW_JNI = NEW_PACKAGE.replace(".", "_")

OLD_PATH_SLASH = OLD_PACKAGE.replace(".", "/")
NEW_PATH_SLASH = NEW_PACKAGE.replace(".", "/")

EXCLUDE_DIRS = {".git", ".gradle", "build", ".idea", "wrapper"}

def replace_in_file(filepath):
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()
    except UnicodeDecodeError:
        # print(f"Skipping binary file: {filepath}")
        return
    except Exception as e:
        print(f"Error reading {filepath}: {e}")
        return

    new_content = content
    new_content = new_content.replace(OLD_PACKAGE, NEW_PACKAGE)
    new_content = new_content.replace(OLD_PATH_SLASH, NEW_PATH_SLASH)
    new_content = new_content.replace(OLD_JNI, NEW_JNI)

    if content != new_content:
        print(f"Updating content in: {filepath}")
        with open(filepath, 'w', encoding='utf-8') as f:
            f.write(new_content)

def main():
    print("Starting batch replacement...")
    
    # 1. Replace content in files
    for root, dirs, files in os.walk("."):
        # Modify dirs in-place to skip excluded directories
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        
        for file in files:
            if file == "rename_package.py":
                continue
            filepath = os.path.join(root, file)
            replace_in_file(filepath)

    print("Content replacement done.")

    # 2. Rename directories
    # We look for 'apatch' directory inside 'bmax' inside 'me'
    # Structure: .../me/bmax/apatch
    
    paths_to_move = []
    
    for root, dirs, files in os.walk("."):
        if ".git" in root or "build" in root:
            continue
        
        # Check for me/bmax/apatch
        # We need to find the 'me' directory first or just look for the sequence
        
        # Let's iterate over dirs to find 'me'
        # Actually, if we find 'apatch', check parent.
        if "apatch" in dirs:
            apatch_path = os.path.join(root, "apatch")
            bmax_path = root
            if os.path.basename(bmax_path) == "yuki":
                me_path = os.path.dirname(bmax_path)
                if os.path.basename(me_path) == "me":
                    # Found it: me_path/bmax/apatch
                    # We want to move apatch_path to me_path/yuki/folk
                    
                    # Target structure: me_path/yuki/folk
                    # 'yuki' might not exist.
                    
                    target_dir = os.path.join(me_path, "bmax", "apatch")
                    paths_to_move.append((apatch_path, target_dir))

    for src, dst in paths_to_move:
        if os.path.exists(src):
            print(f"Moving {src} to {dst}")
            
            # Ensure parent of dst exists (me/yuki)
            os.makedirs(os.path.dirname(dst), exist_ok=True)
            
            if os.path.exists(dst):
                print(f"Target {dst} already exists. Merging...")
                # Move items individually
                for item in os.listdir(src):
                    s = os.path.join(src, item)
                    d = os.path.join(dst, item)
                    if os.path.exists(d):
                         print(f"Warning: {d} already exists. Skipping.")
                    else:
                        shutil.move(s, d)
                try:
                    os.rmdir(src)
                except:
                    print(f"Could not remove {src} (maybe not empty)")
            else:
                shutil.move(src, dst)
            
            # Cleanup empty me/bmax
            bmax_dir = os.path.dirname(src)
            try:
                if not os.listdir(bmax_dir):
                    os.rmdir(bmax_dir)
                    print(f"Removed empty directory: {bmax_dir}")
            except OSError:
                pass

    print("Directory renaming done.")

if __name__ == "__main__":
    main()
