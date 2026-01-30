#!/system/bin/sh
#######################################################################################
# APatch Boot Image Flasher
#######################################################################################

ARCH=$(getprop ro.product.cpu.abi)

# Load utility functions
. ./util_functions.sh

echo "****************************"
echo " ACPatch Boot Image Flasher"
echo "****************************"

BOOTIMAGE=$1
SOURCE_IMAGE=$2

[ -e "$SOURCE_IMAGE" ] || { echo "- $SOURCE_IMAGE does not exist!"; exit 1; }

echo "- Target image: $BOOTIMAGE"
echo "- Source image: $SOURCE_IMAGE"

echo "- Flashing boot image"
flash_image "$SOURCE_IMAGE" "$BOOTIMAGE"

if [ $? -ne 0 ]; then
  >&2 echo "- Flash error: $?"
  exit $?
fi

echo "- Flash successful"
