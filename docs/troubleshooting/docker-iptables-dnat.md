# Troubleshooting: Docker iptables / DNAT Missing Chain on Arch Linux

## Symptom
When attempting to run a container with port publishing (`-p <host_port>:<container_port>`), Docker fails with the error:
```
docker: Error response from daemon: failed to set up container networking: driver failed programming external connectivity on endpoint ...:
Unable to enable DNAT rule: (iptables failed: iptables --wait -t nat -A DOCKER -p tcp -d 0/0 --dport 9100 -j DNAT --to-destination 172.17.0.2:80 ! -i docker0: iptables: No chain/target/match by that name. (exit status 1))
```

## Root Cause
On Arch Linux and modern Linux systems, iptables rules are maintained dynamically in the kernel or nftables subsystem. If the firewall or network manager is restarted or system rules are flushed while the `dockerd` daemon remains active, Docker's default custom chains (`DOCKER`, `DOCKER-FORWARD`, `DOCKER-ISOLATION-STAGE-1`) are removed from the kernel's nat/filter tables.

Because `dockerd` assumes it only needs to initialize these chains upon daemon boot, subsequent `docker run -p` calls fail because the target chain `DOCKER` no longer exists.

## Solutions

### Standard Fix (System Level)
Restarting the Docker systemd service rebuilds the required iptables chains:
```bash
sudo systemctl restart docker
```

### Alternative Architecture Workaround (Zero System Changes)
If systemd services cannot be restarted immediately or root/sudo is unavailable, containers can run using `--network host` (or with an internal reverse proxy/bridge), bypassing Docker's virtual bridge and DNAT rules.

