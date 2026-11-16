#!/usr/bin/env bash
# Przygotowanie świeżego serwera Ubuntu 24.04 pod Narrivy (uruchom jako root).
#
#   bash server-setup.sh app                    # serwer aplikacji (porty 22, 80, 443)
#   bash server-setup.sh engine                 # serwer GPU: sterownik NVIDIA + NVIDIA Container Toolkit
#
# Instaluje: aktualizacje, Docker z oficjalnego repozytorium, firewall (ufw), fail2ban (ochrona SSH),
# automatyczne aktualizacje bezpieczeństwa. Skrypt można uruchomić ponownie.
set -euo pipefail
ROLE="${1:?Podaj rolę: app albo engine}"
[ "$(id -u)" -eq 0 ] || { echo "Uruchom jako root (sudo)."; exit 1; }
export DEBIAN_FRONTEND=noninteractive

echo "==> Aktualizacja systemu"
apt-get update -q
apt-get upgrade -yq
apt-get install -yq ca-certificates curl gnupg git ufw fail2ban unattended-upgrades

echo "==> Docker"
if ! command -v docker >/dev/null; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -q
  apt-get install -yq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
fi
systemctl enable --now docker

if [ "$ROLE" = "engine" ]; then
  echo "==> Sterownik NVIDIA"
  if ! command -v nvidia-smi >/dev/null; then
    apt-get install -yq ubuntu-drivers-common
    ubuntu-drivers install --gpgpu || ubuntu-drivers autoinstall
    NEED_REBOOT=1
  fi
  echo "==> NVIDIA Container Toolkit"
  if ! command -v nvidia-ctk >/dev/null; then
    curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg
    curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
      | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
      > /etc/apt/sources.list.d/nvidia-container-toolkit.list
    apt-get update -q
    apt-get install -yq nvidia-container-toolkit
  fi
  nvidia-ctk runtime configure --runtime=docker
  systemctl restart docker
fi

echo "==> Firewall"
ufw default deny incoming
ufw default allow outgoing
ufw allow OpenSSH
# Uwaga: porty publikowane przez Dockera omijają ufw — dlatego silnik słucha tylko na 127.0.0.1 / adresie prywatnym,
# a Caddy sam wpuszcza wyłącznie serwer aplikacji. Dodatkowo włącz zaporę w panelu Hetznera.
ufw allow 80/tcp
ufw allow 443/tcp
ufw --force enable

echo "==> Ochrona SSH i aktualizacje bezpieczeństwa"
systemctl enable --now fail2ban
dpkg-reconfigure -f noninteractive unattended-upgrades

echo
echo "Gotowe (${ROLE})."
if [ "${NEED_REBOOT:-0}" = "1" ]; then
  echo "Zainstalowano sterownik NVIDIA — uruchom ponownie serwer (reboot), potem sprawdź: nvidia-smi"
fi
