# Remote internet through the school desktop — status and design

This is the one part of the directive that is **BLOCKED**, and it is blocked for a reason that no
amount of app code can fix. This document records exactly what exists, what does not, and what a
working implementation would require. Nothing here is a mockup: there is no VPN code in the app,
because a VPN that does not actually route traffic is worse than none.

## Why it is blocked

Three independent things must all be true for the phone's traffic to leave through the school
network. Only the first is under our control.

| Requirement | State | Notes |
|---|---|---|
| Android side: a `VpnService` that captures packets and forwards them to the desktop | **NOT IMPLEMENTED** | No `VpnService`, no `BIND_VPN_SERVICE`, no `android.net.VpnService` permission exists in the manifest. This is real work, not a small change. |
| Desktop side: an internet gateway that accepts forwarded packets, NATs them, and returns replies | **NOT IMPLEMENTED** | Requires WireGuard or an equivalent tunnel plus `iptables`/`nftables` NAT rules with administrator rights on the desktop. |
| Permission to use the school network this way | **UNKNOWN, NOT OURS TO GRANT** | Routing a phone's traffic through a school machine is the school's decision. It needs the administrator's approval and must comply with the school's acceptable-use policy. |

The third item is the decisive one. Even a technically perfect tunnel would be doing something the
school may prohibit, and the directive itself says to respect firewall, administrator and school
network permissions. I cannot verify those permissions, so I am not shipping a tunnel that assumes
them.

## What a correct implementation looks like

This is the design I would build, with the honest scope of each part.

```
   phone (Android)                    school desktop                    internet
   +--------------+    tunnel        +----------------+   NAT        +---------+
   | VpnService   |  (WireGuard,     | wg0 interface  |  (iptables)  |  ...    |
   | TUN device   |  UDP 51820)      | IP forwarding  |              |         |
   | packet loop  | <==============> | DNS forwarder  | <==========> |         |
   +--------------+                  +----------------+              +---------+
```

1. **Transport: WireGuard.** Chosen because it is open source, small, auditable, and available for
   both Android (the official app's kernel/userspace code) and every desktop OS. The peer keys are
   generated per pairing, exactly like the remote-control pairing already implemented, and the
   desktop's public key is the only thing the phone needs beyond its address.

2. **Android side.** A `VpnService` that calls `Builder.addRoute("0.0.0.0", 0)` for a full tunnel or
   specific prefixes for a split tunnel, sets `addDnsServer(...)` to the desktop's forwarder,
   establishes the TUN device, and runs the WireGuard userspace stack against it. `setBlocking(true)`
   and a fail-closed design: if the tunnel drops, traffic stops rather than falling back to the
   carrier network, which is what leak protection means.

3. **Desktop side.** `wg-quick` brings up `wg0` with the phone as an allowed peer. Then:
   - `net.ipv4.ip_forward=1` to forward packets.
   - `iptables -t nat -A POSTROUTING -o <uplink> -j MASQUERADE` to NAT them out.
   - A DNS forwarder bound to the tunnel address, so DNS goes through the tunnel and cannot leak.
   - `iptables` forwarding rules that accept only the tunnel interface and drop everything else.

4. **Full tunnel vs split tunnel.** Full tunnel is `AllowedIPs = 0.0.0.0/0` on the phone and
   `addRoute("0.0.0.0", 0)`. Split tunnel is a narrower `AllowedIPs` list plus matching routes, so
   only chosen destinations use the school network.

5. **Leak protection and monitoring.** DNS is forced to the tunnel address; if the tunnel is down the
   VPN is torn down rather than left half-configured. The app would show the tunnel state, the bytes
   transferred, and the exit address, and it would verify the exit by fetching a public address
   service through the tunnel and comparing it to the desktop's own external address.

## Why I did not write the app half anyway

A `VpnService` that builds a TUN device but has no gateway to send packets to is a mockup that would
show "connected" while routing nothing. The directive explicitly forbids simulated success. The
desktop gateway also cannot be tested here: this container has no second network path, no
administrator rights over a school network, and no phone. Every claim about "traffic actually exits
through the school network" would be unverifiable, so I make none.

## What I need to proceed

1. Written confirmation from the school's network administrator that routing the phone's traffic
   through the desktop is permitted, with any conditions (which ports, which subnets, whether DNS is
   allowed).
2. A decision on full tunnel or split tunnel, and which destinations the split tunnel should carry.
3. The desktop's platform (Windows, Linux or macOS), because the gateway setup differs. Windows
   would need the WireGuard for Windows client plus `New-NetNat` or an equivalent; Linux uses the
   `iptables` rules above.

With those, the Android `VpnService` and the desktop gateway are both buildable and testable. Until
then this item stays BLOCKED, not partially done.
