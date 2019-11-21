#!/bin/sh

APP=lila-ws-2.0
REMOTE=$1
REMOTE_DIR="/home/lila-ws"

package_dir="target/universal"
package="$package_dir/$APP"

echo "Deploy to server $REMOTE:$REMOTE_DIR"

rm $package.zip
rm -rf $package

sbt universal:packageBin

if [ $? != 0 ]; then
  echo "Deploy canceled"
  exit 1
fi


unzip $package.zip -d $package_dir

RSYNC_OPTIONS=" \
  --archive \
  --no-o --no-g \
  --force \
  --delete \
  --progress \
  --compress \
  --checksum \
  --verbose \
  --exclude RUNNING_PID \
  --exclude '.git/'"

include="$package/bin $package/lib"
rsync_command="rsync $RSYNC_OPTIONS $include $REMOTE:$REMOTE_DIR"
echo "$rsync_command"
$rsync_command
echo "rsync complete"

read -n 1 -p "Press [Enter] to continue."

echo "Restart lila-ws"
ssh $REMOTE "systemctl restart lila-ws"

echo "Deploy complete"

