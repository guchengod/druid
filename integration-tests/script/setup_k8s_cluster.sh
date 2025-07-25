#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -e
set -x

export INSTALL_K3S_VERSION=v1.33.2+k3s1
export KUBECONFIG=$HOME/.kube/config

# Launch K8S cluster
curl -Lo kubectl https://dl.k8s.io/release/v1.33.0/bin/linux/amd64/kubectl && chmod +x kubectl && sudo mv kubectl /usr/local/bin/

## Errors are usually found in journalctl logs, hence we use `|| true` to avoid script failure
curl -sfL https://get.k3s.io | bash /dev/stdin  --docker || true
systemctl status k3s.service || true
journalctl -u k3s.service

mkdir -p $HOME/.kube
sudo cp /etc/rancher/k3s/k3s.yaml $KUBECONFIG
sudo chmod 777 $KUBECONFIG
echo "Setup K8S Cluster Done!"
