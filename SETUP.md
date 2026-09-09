# Setup — playing Bleach with friends

How to get the mod built, onto a server, and into a state where five people can actually fight each
other. Companion to `PRD.md` (what the mod does) and `BALANCE.md` (every number).

**Everyone needs the same three things:** Minecraft **1.21.1**, Fabric Loader **0.19.5+**, and
Fabric API **0.116.17+1.21.1**. Not 1.21.4, not Forge, not NeoForge. A player on the wrong
Minecraft version cannot join at all; a player missing Fabric API gets a crash on world load with a
`ClassNotFoundException` naming a `net.fabricmc.fabric.api` class.

---

## 1. Build the jar

From the project root:

```bash
./gradlew build
```

The mod lands at **`build/libs/bleach_mod-2.1.0.jar`**. Ignore `bleach_mod-1.0.0-sources.jar` — that
is the source bundle and does nothing in a mods folder.

Send that one jar to everyone who is playing. There is no separate client and server build; the same
file goes in both places (`fabric.mod.json` declares `"environment": "*"`).

---

## 2. Everyone installs Fabric + the mod

Each player, including whoever hosts:

1. Download the **Fabric installer** from <https://fabricmc.net/use/installer/>, run it, pick
   Minecraft **1.21.1**, install.
2. Download **Fabric API 0.116.17+1.21.1** from
   <https://modrinth.com/mod/fabric-api/versions?g=1.21.1>.
3. Drop **both** `fabric-api-0.116.17+1.21.1.jar` and `bleach_mod-2.1.0.jar` into the mods folder:
   - Windows: `%APPDATA%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
4. Launch the **`fabric-loader-1.21.1`** profile in the Minecraft launcher, not `1.21.1`.

Confirm it loaded: the log line `[bleach_mod] Bleach mod initialized` appears during startup, and a
blue spiritual-pressure bar sits above the right side of the hotbar in-game.

### If you use a launcher instead

MultiMC, Prism and the Modrinth App all handle steps 1–4 for you — make a 1.21.1 Fabric instance,
add Fabric API from the built-in mod browser, then drag `bleach_mod-2.1.0.jar` into the instance's
mods folder. This is the less error-prone route and worth suggesting to anyone who has not
hand-installed Fabric before.

---

## 3. Run the server on your own machine

You are hosting locally and letting friends in from outside, so the order is: get the server running
and working locally first, then put the networking layer in front of it. Do not skip the first half
— if the server is broken, the tunnel will only tell you the connection failed, and you will spend
the evening debugging the wrong layer.

Two routes are written up below. **§3.3 Tailscale is the one to use.** §3.4 remote.it is kept as the
fallback if Tailscale is blocked on someone's network or somebody flatly will not install a VPN.

| | Tailscale (§3.3) | remote.it (§3.4) |
|---|---|---|
| Port friends type | **25565**, the normal one | a proxy port like `33001` |
| Address stability | stable forever | can move between sessions |
| Latency | direct WireGuard where possible | direct or relayed via their cloud |
| Who can reach the port | only devices you authorised | anyone holding the address |
| Setup per friend | install Tailscale, accept a link | nothing, if you hand out the address |
| Safe with offline mode (§3.2) | **yes** | no — see the warning there |

### 3.1 Get the Fabric server running

1. Download the **Fabric server launcher** for 1.21.1 from <https://fabricmc.net/use/server/>.
2. Put it in an empty folder, run it once, accept the EULA (`eula=true` in `eula.txt`), run it again.
3. Create a `mods` folder next to the server jar and drop in **Fabric API** and
   **`bleach_mod-2.1.0.jar`** — the same two files the clients have.
4. In `server.properties`:

   ```properties
   server-port=25565
   server-ip=
   online-mode=true
   pvp=true
   difficulty=normal
   max-players=8
   ```

   - **Leave `server-ip` blank.** It binds the server to a single interface, and a server bound to
     your LAN address is one the tunnel cannot reach. (§3.3 has an optional hardening variant of
     this that is safe *because* it binds to the Tailscale interface deliberately.)
   - **`pvp=true` is not optional.** With it off, every player-versus-player mechanic in the mod —
     which is most of it — silently does nothing.
   - `online-mode=true` is fine over either tunnel. Authentication is between each client and Mojang
     and does not care what route the connection took. **If you need unauthenticated clients to
     join, read §3.2 before changing this** — it is not just a toggle, it changes how player
     identity works, and this mod stores progression against it.
5. Start it and confirm `Done (…)! For help, type "help"` in the console, then connect **from the
   host machine itself** to `localhost:25565`. If that fails, fix it before going any further.
6. Op everyone who will be testing. From the server console:

   ```
   op <their_minecraft_name>
   ```

   `/bleach` requires **permission level 2**. **Op the whole group, not just yourself** — see the
   note in §5 for why that is not optional.

### 3.2 Letting unauthenticated ("cracked") clients join

Set this in `server.properties`:

```properties
online-mode=false
```

That is the whole change. `online-mode` is a stock vanilla setting — it also exists for LAN parties,
air-gapped networks and proxy backends — and turning it off tells the server to stop asking Mojang
whether a connecting player owns the game. For the record: it does not grant anyone a licence, and
Mojang's EULA is between them and each player rather than something I can settle here. Your call.
What follows is what the setting actually does to your server.

**They still need the same mods.** Offline mode changes authentication and nothing else. Every
player still needs Fabric Loader 1.21.1, Fabric API and `bleach_mod-2.1.0.jar`, exactly as in §2.
Some third-party launchers make installing Fabric awkward; whatever they use has to support
**Fabric for 1.21.1** or they cannot join a modded server at all.

#### What breaks: identity

With `online-mode=false`, **the server believes whatever username a client claims.** There is no
verification. Anyone who can reach the port can connect as any name, including yours — and since op
is granted by name, connecting as an opped name means arriving with `/bleach`, `/op` and everything
else. That is not a subtle risk, it is the documented behaviour of the setting.

**This is the strongest argument for Tailscale over remote.it.** On a tailnet, "anyone who can reach
the port" is a list you personally authorised, device by device. With a public remote.it proxy
address it is anyone the address was ever forwarded to. If you are running offline mode, use §3.3
and treat §3.4 as unavailable rather than merely worse.

Minimum sensible hardening either way:

```properties
online-mode=false
white-list=true
enforce-whitelist=true
```

Then, from the server console:

```
whitelist add <name>          # one per player, exactly as they will type it
whitelist reload
op <name>                     # console only — never trust an in-game /op in offline mode
```

The whitelist stops *strangers*: a random scanner cannot join as `Notch` if `Notch` is not on the
list. It does **not** stop impersonation between people who already know each other's names, because
a whitelisted name is still just a claimed string. Among five friends that is usually fine — just
understand it is a social guarantee, not a technical one.

#### What breaks: this mod's progression

This part is specific to Bleach and worth reading twice.

`SpiritualData` — Soul Level, SPX, chosen kit, zanpakutō — is a **persistent player attachment**, so
it lives in `world/playerdata/<uuid>.dat`. The `WorldSoulLevel` roster is a UUID-keyed table too. In
online mode that UUID comes from Mojang and is permanent. In offline mode the server derives it from
the username instead, via `UUID.nameUUIDFromBytes("OfflinePlayer:" + name)`. Three consequences:

- **Progression is tied to the name, not the person.** Anyone who changes the username they connect
  with gets a brand-new character: Soul Level 1, no kit, no blade. Tell everyone to pick a spelling
  and keep it, capitalisation included.
- **Impersonation inherits progression.** Connecting as someone else's name does not merely borrow
  their identity, it loads their character — their Soul Level, their zanpakutō, and the permanent
  kit choice that `/bleach kit clear` exists to undo.
- **Switching `online-mode` later orphans everyone.** Flip it back to `true` and every player gets
  their real Mojang UUID, which points at a `playerdata` file that does not exist. Everyone restarts
  at SL 1 while their old progression sits on disk under the offline UUID. **Decide before you start
  playing, not after.** Migrating afterwards means renaming `playerdata` files by hand, and the
  `WorldSoulLevel` roster still has to be rebuilt.

For the Phase 12 tuning pass none of this matters much — everyone is going to run `/bleach sl set
20` in §5 regardless, and a wiped character costs one command to restore.

### 3.3 Expose it with Tailscale — recommended

Tailscale is a mesh VPN, not a proxy. Your friends' machines join a private network with yours and
then reach the server as though they were sitting on your LAN. Nothing is published to the internet,
the port stays 25565, and the address never changes.

**Before you start:** Tailscale **Funnel does not work for Minecraft.** Funnel only carries HTTPS on
a fixed set of ports, so it cannot serve a raw TCP game connection — ignore any guide that suggests
it. Everyone who plays installs Tailscale. That is the trade for everything else on this list.

#### On the host machine

1. **Make an account** at <https://login.tailscale.com/start> — sign in with Google, GitHub,
   Microsoft or similar. There is nothing to configure.
2. **Install Tailscale** from <https://tailscale.com/download> on the machine running the Fabric
   server, and sign in. It joins your *tailnet* and picks up an address in the `100.x.y.z` range.
3. **Find the server's address.** In the Tailscale admin console at
   <https://login.tailscale.com/admin/machines>, or by running:

   ```bash
   tailscale ip -4          # -> 100.x.y.z
   tailscale status         # also shows the MagicDNS name
   ```

4. **Rename the machine** to something you will recognise — `bleach-server` — from the admin
   console. With MagicDNS on (it is on by default for new tailnets), that name becomes a hostname
   your friends can type instead of the numeric address.
5. **Check the server is reachable over the tailnet** before involving anyone else: connect in
   Minecraft to `100.x.y.z:25565` from the host itself. If `localhost` worked in §3.1 and this does
   not, the server is bound to the wrong interface — check that `server-ip` is blank.

#### Getting your friends on

Use **node sharing**, not tailnet invitations. Sharing hands out access to one machine; inviting
makes someone a full user of your tailnet with access to everything on it, and eats your free-plan
user allowance. The free Personal plan is generous on devices but small on users (it has been 3),
which is exactly the limit sharing exists to avoid — so check your plan's current numbers before
inviting five people.

1. In the admin console, open the `bleach-server` machine → **Share**.
2. Generate a share link and send it to each friend individually.
3. They install Tailscale, sign in with their own account (a personal tailnet of their own is
   created automatically — that is fine and free), and accept the link.
4. `bleach-server` now appears in their Tailscale machine list.

#### Connecting

Friends open Minecraft → Multiplayer → Add Server, and enter **either**:

```
100.x.y.z                                  # the numeric tailnet address
bleach-server.<your-tailnet>.ts.net        # the full MagicDNS name
```

Port 25565 is the default, so they can leave it off entirely. Two notes:

- **Shared users need the *fully qualified* MagicDNS name**, `bleach-server.tailnet-name.ts.net`,
  not the bare `bleach-server`. The short form resolves inside your tailnet, not theirs. The numeric
  `100.x.y.z` always works and is the thing to fall back on when a name will not resolve.
- **Tailscale must be running and connected** on their machine whenever they play. This is the most
  common "it worked yesterday" cause on this route — the app quit, or they signed into the wrong
  account.

#### Optional hardening: bind the server to the tailnet only

Once Tailscale is working, you can make the server unreachable from anywhere else — your LAN and any
accidental port forward included — by binding it to the tailnet address:

```properties
server-ip=100.x.y.z
```

The trade is that `localhost:25565` stops working on the host, so connect via the `100.x.y.z`
address there too, and you have to update this line if the tailnet address ever changes (it is
stable in practice, but it is not a promise). **If you are running offline mode from §3.2, this is
worth doing** — it makes an unauthenticated server unreachable except from devices you authorised.

### 3.4 Expose it with remote.it — the fallback

Use this if Tailscale is blocked on someone's network or a friend refuses to install a VPN client.
**Do not pair it with offline mode** (§3.2) unless you are sharing the device rather than handing
out the proxy address.

remote.it runs a small agent on the host that dials out to its network and gives you an address your
friends connect through. Because the agent makes an **outbound** connection, your router needs no
configuration. remote.it revises its console fairly often, so treat the labels below as "look for
something that means this" rather than exact button text.

1. **Make an account** at <https://app.remote.it> (free tier is fine for a group this size).
2. **Install remote.it Desktop** from <https://remote.it/download/> on the host machine and sign in.
3. **Register the device**, naming it something you will recognise (`bleach-server`).
4. **Add a service** pointing at the Minecraft server:

   | Field | Value |
   |---|---|
   | Service type | **TCP** (pick the Minecraft preset if your version offers one — same thing) |
   | Local host | `127.0.0.1` |
   | Local port | `25565` |
   | Name | `Minecraft` |

   Server-list ping and gameplay both run over that one TCP port, so a single TCP service is all you
   need. Nothing to add for UDP.
5. **Get the connection address.** Start the connection; remote.it gives you something like
   `proxy71.rt3.io:33001` — a hostname plus a port that is **not** 25565.
6. **Give it to your friends**, either by sharing the device (they install remote.it too and get a
   stable `localhost:<port>` on their machine — better latency, and the only acceptable option in
   offline mode) or by handing out the proxy address directly (zero setup for them, but anyone with
   the string can reach the server).
7. **Add it in Minecraft** with the port included. Minecraft assumes 25565 when you omit one, which
   is the wrong port here and fails with nothing useful in the log.

Gotchas specific to this route:

- **The proxy address can change** across connection or host restarts. Re-check it each session; the
  symptom is everyone who worked yesterday timing out today.
- **Expect worse ping than Tailscale.** Relayed connections add real latency, which in this mod
  shows up as Flash Step feeling mushy and melee trades resolving oddly. Do not read combat feel as
  a *balance* problem until you have checked ping.
- **Free tier limits change** — check your account rather than trusting a number written here.
- **Turn the connection off when you stop playing.** If you handed out the raw address, that is the
  only access control this route has.

### 3.5 The alternative, if the network layer fights you

Open a singleplayer world, `Esc` → **Open to LAN**, **Allow Cheats ON** (without it no `/bleach`
command works). Everyone on the same physical network joins from the Multiplayer screen.

This also works *over Tailscale* with one caveat: LAN discovery is a local broadcast and does not
cross a tailnet, so the world will not appear in anyone's server list automatically. They have to
use **Direct Connect** with `100.x.y.z:<port>`, and the port is the random one Minecraft prints in
chat when you open to LAN, not 25565.

Either way, the world only exists while the host has the game open, and the host's frame rate
carries the whole server. It is a good two-minute way to rule out the networking layer entirely, and
a poor way to run an ongoing game.

---

## 4. First five minutes in-game

New players spawn holding an **Asauchi**. Right-click it to open a one-row picker with the five
zanpakutō; click one. **The choice is permanent** — barring `/bleach kit clear`, which is why that
command exists.

| Key | Does |
|---|---|
| `X` | Draw / sheathe your zanpakutō |
| `V` | Flash Step |
| `R` | Shikai (toggle) |
| `G` | Bankai (toggle) |
| `LEFT ALT` | Spiritual Flex — **hold**, not tap |
| `K` | Soul Stats screen |
| `B` | Master on/off switch (operators only) |

All rebindable in Options → Controls → **Bleach**.

Two rules that catch everyone on day one:

- **Shikai and Bankai need the blade drawn.** Flash Step and Flex do not — they are pressure, not
  technique.
- **Bankai needs your bar at 95%, Shikai at 65%.** Both thresholds fall as your Soul Level rises.
  If nothing happens when you press `G`, look at the bar first.

---

## 5. Levelling up without waiting a month

SPX is only awarded for a **clean kill with the drawn zanpakutō**. If anything else touched the
target — a second player, a stray skeleton, fall damage, your own wolf — it pays nothing, and that
taint never clears. A bow kill pays nothing either. This is deliberate, and it is the single thing
people misread as the mod being broken.

There is also a daily cap of **200 SPX** (scaled by World Soul Level and catch-up), rolling at each
Minecraft dawn.

So for an evening of testing, skip the grind:

```
/bleach sl set 20        # jump straight to max Soul Level
/bleach spx add 500      # or bank SPX and watch the real level-up loop run
/bleach sp set 100       # refill the pool
/bleach exertion set 0   # clear accumulated transformation debt
```

> **Every `/bleach` command acts on whoever runs it.** None of them takes a player argument, so you
> cannot set a friend's Soul Level for them — they have to be opped and run it themselves. That is
> why §3.1 says to op the whole group. The two exceptions are `/bleach toggle` and `/bleach wsl`,
> which are server-wide whoever runs them.

To make the world feel like a long-running server without playing one:

```
/bleach wsl set 18                # pin World Soul Level — mobs hit harder, kills pay more
/bleach wsl clear                 # back to the real computed value
```

Give everyone the same Soul Level before a PvP session. The level gap drives damage scaling, the
Flex tier table and SPX payouts all at once, so an SL-20 player against an SL-1 player is not a
fight, it is a demonstration.

---

## 6. Fight setups worth trying

Each of these exercises a different system, and each is a reasonable evening.

**Two players, same kit, same level.** The cleanest read on whether the pool economy feels right.
Watch what happens after someone burns Bankai: the exertion debt suppresses their regen for a long
time, and the second engagement is decided by who spent better in the first.

**Bankai roulette.** Everyone at SL 20, everyone in Bankai at once, last one standing wins. Bankai
refills your bar to full on entry as a **loan** and drains 5 SP/s; drop to zero and you revert
whether you like it or not. On revert, any surplus above what you entered with is clawed back — so
`/bleach test clawback` exists to prove the loan cannot be farmed.

**Sui-Feng's Bankai on a superflat.** Build a flat arena, put eight mobs in it, and set it off. This
is the heaviest thing in the mod — a rooted three-second wind-up, then a 24-block sweep and a
2,000-block crater excavated 200 blocks per tick. Watch the server tick time; it should stay under
40 ms. Then check that nothing dropped as an item and no gravel cascaded.

**Rukia over an ocean.** Bankai on water. It freezes to frosted ice, which melts on its own within
about a minute of you leaving — no cleanup, by design. On grass, snow builds up and **stays**, which
is also by design.

**Shinji against anything.** Bankai inverts WASD for every player in range and makes mobs stagger
drunkenly. This is the most likely thing in the mod to need retuning, so it is the most useful thing
to get real reactions to. Mouse-look is never touched — if someone reports their camera inverting,
that is a bug and worth telling me about.

---

## 7. Tuning it while you play

Every number lives in one file, created the first time the mod runs:

```
<server folder>/config/bleach_mod/tuning.json
```

It starts **empty on purpose** — it stores only the values you change. Anything not in it follows
the mod's own default and updates automatically when you update the mod, so upgrading never
silently pins you to last version's balance and never wipes the tuning you did do.

To see what there is to change, open the generated listing beside it:

```
<server folder>/config/bleach_mod/tuning-defaults.json
```

That file shows every key and the default this build ships with. It is rewritten on every launch
and never read back — editing it does nothing. Copy the line you want into `tuning.json` and change
it there.

Edit it, then in-game:

```
/bleach reload
```

No restart. The file is **server-side only and never reaches clients**, so tune it on the host and
nobody else has to do anything — which is the main practical reason to run a dedicated server rather
than Open to LAN.

`BALANCE.md` documents every key. The seven most likely to be wrong on first contact, in the order
worth touching them, are in `IMPLEMENTATION_PLAN.md` §12 — `SHINJI_MOB_INVERT_CHANCE` first.

Each kit has an operator command that prints its live numbers with the inputs that produced them,
which beats guessing from the config file:

```
/bleach fs        /bleach flex      /bleach cleave
/bleach yama      /bleach rukia     /bleach suifeng    /bleach shinji
```

---

## 8. When something goes wrong

**Is it this mod?** Press `B`, or run `/bleach toggle false`. That freezes the pool, drops every
keypress, hides the HUD and reverts anyone mid-transformation. If the problem survives that, it is
not this mod. Press `B` again to bring it back.

### Connection — any route

| Symptom | Cause |
|---|---|
| Nobody can connect, you can't either | Fabric server is not running — a tunnel forwards a port, it does not start anything |
| Can't connect, "incompatible" | Wrong Minecraft version — everyone needs 1.21.1 |
| "Failed to verify username" / "Bad login" | `online-mode=true` with an unauthenticated client — see §3.2 |
| "You are not white-listed on this server" | `whitelist add <name>` then `whitelist reload` from the console |
| You can join on `localhost` but nobody else can | `server-ip` is set to the wrong interface in `server.properties` |

### Connection — Tailscale

| Symptom | Cause |
|---|---|
| Worked yesterday, cannot connect today | Tailscale is not running or is signed into the wrong account on their machine |
| `bleach-server` will not resolve for a friend | Shared users need the full `bleach-server.<tailnet>.ts.net`; the numeric `100.x.y.z` always works |
| Friend never got access | Share link not accepted, or you invited them as a user and hit the free-plan user limit |
| Host can reach `100.x.y.z` but friends cannot | Machine shared with the wrong account, or their Tailscale is connected to a different tailnet |
| Open-to-LAN world invisible to everyone | Expected — LAN discovery does not cross a tailnet. Use Direct Connect (§3.5) |

### Connection — remote.it

| Symptom | Cause |
|---|---|
| "Can't connect to server", no log entry | Port omitted. `proxy71.rt3.io` alone means 25565; you need `proxy71.rt3.io:33001` |
| Worked yesterday, times out today | The proxy host/port moved. Re-read it in the console and re-send |
| Everyone connects but combat feels laggy | Relayed rather than peer-to-peer route. Check ping before blaming balance |

### The mod

| Symptom | Cause |
|---|---|
| Crash on load, `net.fabricmc.fabric.api...` | Fabric API missing from that machine's mods folder |
| No bar, no keybinds, no crash | Launched vanilla `1.21.1` instead of the `fabric-loader-1.21.1` profile |
| `/bleach` says unknown command | Not opped, or cheats off on a LAN world |
| Can't set a friend's level | Correct — commands only affect the caller; op them instead |
| `R`/`G` do nothing | Blade sheathed (`X`), or bar below the 65% / 95% gate |
| Kills award no SPX | Something else damaged the target, or the killing blow was not the drawn blade — usually working as intended |
| PvP mechanics inert | `pvp=false` in `server.properties` |
| One player lost all progression | Offline mode, and they changed the spelling of their username (§3.2) |
| Everyone reset to SL 1 at once | `online-mode` was flipped after people had played — every UUID changed |
| Stuck at max HP after relogging | Report it — the health modifier is meant to be idempotent across rejoins |

Server logs are in `logs/latest.log`; anything from this mod is prefixed `[bleach_mod]`.

---

## 9. What to tell me afterwards

The mod is code-complete through Phase 11 and has never been played by more than one person. Phase
12 is the tuning pass, and it is *your session*, not more building. The numbers most likely to be
wrong are behavioural rather than arithmetic, so what is worth writing down is:

- Did Bankai feel like a decision or an obvious button? The claw-back is meant to make it a real
  cost.
- Did exertion make the *second* fight interesting, or just annoying?
- Did Flex read as pressure, or as an unavoidable stun?
- Did Shinji's inversion read as drunk, or as broken controls?
- Anything that made someone say "that can't be right."

Tick times above 40 ms, items dropping from a crater, or a zanpakutō going missing are bugs, not
tuning. Those are worth an immediate report with the log.

**Check ping before reporting combat feel.** Over a tunnelled connection, latency and balance are
indistinguishable from the inside, and the first evening on a new route is exactly when you are most
likely to mistake one for the other. Tailscale's direct path usually makes this a non-issue; a
relayed route does not.
