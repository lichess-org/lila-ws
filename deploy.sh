#!/bin/sh

REMOTE=$1
REMOTE_DIR="/home/lila-ws"
TARBALL=lila-ws-1.0-SNAPSHOT.tgz

echo "Deploy to server $REMOTE:$REMOTE_DIR"

if [ $? != 0 ]; then
  echo "Deploy canceled"
  exit 1
fi


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

stage="target/universal/stage"
include="$stage/bin $stage/lib $stage/conf"
rsync_command="rsync $RSYNC_OPTIONS $include $REMOTE:$REMOTE_DIR"
echo "$rsync_command"
$rsync_command
echo "rsync complete"

read -n 1 -p "Press [Enter] to continue."

echo "Restart lila-ws"
ssh $REMOTE "systemctl restart lila-ws"

echo "Deploy complete"

