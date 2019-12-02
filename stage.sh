#!/bin/sh

APP=lila-ws-2.0

package_dir="target/universal"
package="$package_dir/$APP"

echo "Build $APP"

rm $package.zip
rm -rf $package

sbt universal:packageBin

if [ $? != 0 ]; then
  echo "Build canceled"
  exit 1
fi

unzip $package.zip -d $package_dir
