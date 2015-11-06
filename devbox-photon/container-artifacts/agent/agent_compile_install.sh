#!/bin/bash -xe


export TERM="vt100"

#
# Point to the openly available vibauthor
#
sed -i 's/$BINARIES\/bin\/vibauthor/vibauthor/' /esxcloud/python/vib/create_vib.sh

#
# Compile agent
#
cd /esxcloud/python && make clean dist && make vib-only

#
# Install agent
#
INSTALL_PATH="/usr/lib/esxcloud/agent"
RELEASE_PATH="/esxcloud/python/dist"

rm -rf $INSTALL_PATH
mkdir -p $INSTALL_PATH

virtualenv $INSTALL_PATH
. $INSTALL_PATH/bin/activate; pip install -q -f file://${RELEASE_PATH} photon.controller.agent
