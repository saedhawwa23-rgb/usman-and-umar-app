# Remote internet through the school desktop — status and design

This document used to say the feature was **BLOCKED** because "there is no VPN code in the app".
That is no longer true: as of 3.13.0 the app ships a real `VpnService` gateway. This page now
records what exists, what it still depends on, and what has not been verified.

## What exists now

| Part | State | Where |
|---|---|---|
| Phone: `VpnService` + WireGuard Go backend | **IMPLEMENTED, not device-verified** | `app/.../core/remote/gateway/GatewayTunnelService.kt` |
| Phone: encrypted peer/private-key storage | **IMPLEMENTED, unit-tested** | `app/.../core/remote/gateway/GatewayKeyStore.kt` |
| Phone: full/split tunnel switch, kill switch | **IMPLEMENTED, unit-tested** | `RemoteControlViewModel.startGateway/stopGateway`, `engageKillSwitch` |
| Desktop: publishes its WireGuard public key | **IMPLEMENTED, unit-tested** | `desktop-agent/.../GatewayIdentityFile.kt`, `HelloAck` |
| Desktop: `wg-quick` config template | **IMPLEMENTED** (template only) | `remote-protocol/.../gateway/DesktopGatewayConfig.kt` |
| Desktop: actually NATs the phone's traffic | **NOT DONE — owner's step** | needs `iptables`/`nftables` and admin rights |
| Permission to use the school network this way | **STILL NOT OURS TO GRANT** | see below |

## What the app does, precisely

The phone half is complete: it establishes a TUN device, runs the WireGuard userspace stack against
it, routes `0.0.0.0/0` and `::/0` (full tunnel) or a narrower `AllowedIPs` list (split tunnel),
pins DNS to the desktop's tunnel address, and tears the tunnel down rather than leaving it half-up.

The desktop half is deliberately partial. The agent generates the key pair, advertises the public
key to the phone, and writes a `wg-quick` config the owner can load into the desktop's own
WireGuard client. It does **not** run `iptables`, enable IP forwarding, or reconfigure the machine's
network — that needs administrator rights the app does not have and should not take on its own.

So the remaining desktop work is a one-time manual step:

```bash
sudo sysctl -w net.ipv4.ip_forward=1
sudo iptables -t nat -A POSTROUTING -o <uplink> -j MASQUERADE
sudo wg-quick up ./agent-gateway.conf
```

Until that is done, the tunnel comes up and the phone routes traffic into it, but nothing forwards
those packets onward. That failure is silent at the network level, which is why the desktop step is
called out here rather than left implicit.

## The permission question is unchanged

Routing a phone's traffic through a school machine is the school's decision, not the app's. It needs
the network administrator's approval and must comply with the acceptable-use policy. The app
implements the mechanism; it cannot grant the permission, and shipping the mechanism is not a claim
that using it is allowed.

## What has not been verified

No physical device was available while this was written. Unverified:

- `VpnService.prepare()` consent and the TUN device on a real phone.
- Whether packets actually traverse the tunnel end to end.
- Kill-switch behaviour while a tunnel is live.
- The desktop's `wg-quick` template against a real WireGuard client.

Everything in the "IMPLEMENTED, unit-tested" rows above is covered by unit tests that run in CI;
none of it has been exercised on hardware.
