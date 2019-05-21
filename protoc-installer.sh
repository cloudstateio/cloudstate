#!/bin/sh

# Argument 1 is Protobuf distribution version number, e.g. 3.7.1
set -e

DISTDIR="protoc_installation"
DISTNAME="protoc-$1-linux-x86_64.zip"
DISTURL="https://github.com/protocolbuffers/protobuf/releases/download/v$1/$DISTNAME"
echo "Installing Protoc $1"
wget --tries=2 --retry-connrefused $DISTURL
unzip -q $DISTNAME -d $DISTDIR
sudo cp $DISTDIR/bin/protoc /usr/local/bin/
sudo cp -r $DISTDIR/include/* /usr/local/include/
rm -rf $DISTDIR
echo "Protoc $1 installed"