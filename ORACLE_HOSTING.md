# Free server on Oracle Cloud (Java + Bedrock)

Oracle's **Always Free** tier gives you a server with up to **4 CPU cores and 24 GB RAM** at no cost, forever. That is
more than enough for you and your friends. You never pay as long as you **stay on the free tier** (don't click
"Upgrade to Pay As You Go").

Two things to know first:
* Oracle asks for a **card** when you sign up, only to check you're a real person. You may see a small temporary
  hold that is given back. You are not charged on the free tier.
* Oracle can **reclaim free servers that sit idle** for a long time (almost no CPU use for 7 days). If nobody plays
  for a week or more, back up your world (step 7) so you never lose it.

Everything below works **from your phone** (no PC needed).

---

## 1. Create the account

1. Go to **https://www.oracle.com/cloud/free/** and tap **Start for free**.
2. Fill in your details. For **Home Region**, pick one **close to you and your friends**.
   **It can't be changed later.**
3. Add the card for verification, finish, and wait for the "account ready" email (can take a few minutes).

## 2. Create the server

1. Log in to **https://cloud.oracle.com**.
2. Menu (☰) → **Compute** → **Instances** → **Create instance**.
3. **Name:** `minecraft`.
4. **Image and shape** → **Edit**:
   * **Image:** Change image → **Canonical Ubuntu** → **22.04** (or 24.04).
   * **Shape:** Change shape → **Ampere** → **VM.Standard.A1.Flex** → set **4 OCPUs** and **24 GB** memory.
     It should say **"Always Free-eligible"**. If it doesn't, you picked the wrong shape.
5. **Networking:** leave the defaults, but make sure **"Assign a public IPv4 address"** is on.
6. **Add SSH keys:** choose **Generate a key pair for me** and **download the private key**
   (a file like `ssh-key-2026-xx-xx.key`). **Keep it safe:** it's the only way into your server.
7. Tap **Create**. After a minute it shows **Running**. Write down the **Public IP address**
   (like `129.151.x.x`).

> **"Out of capacity"?** Popular regions are sometimes full. Try again later (early morning often works), or
> try 2 OCPU / 12 GB first. Don't upgrade your account to get around it, because that can lead to charges.

## 3. Open the Minecraft ports (Oracle side)

1. On your instance page, click the **Subnet** link (under "Primary VNIC").
2. Click the **Default Security List** → **Add Ingress Rules**, and add **two** rules:

| Source CIDR | IP Protocol | Destination Port | What for |
|---|---|---|---|
| `0.0.0.0/0` | TCP | `25565` | Java players |
| `0.0.0.0/0` | UDP | `19132` | Bedrock players |

## 4. Connect to the server (from your phone, using Oracle Cloud Shell)

1. In the Oracle website, tap the **Cloud Shell** icon at the top right (it looks like `>_`). A black terminal
   opens at the bottom of the page. (On a phone, turn it sideways or use "Desktop site" if the icon is hidden.)
2. In Cloud Shell, tap the **gear / menu** icon → **Upload** → pick the private key you downloaded in step 2.
3. Type this (use your key's file name and your server's IP):
   ```
   chmod 600 ssh-key-*.key
   ssh -i ssh-key-*.key ubuntu@YOUR_SERVER_IP
   ```
   Type `yes` the first time. You are now inside your server.

## 5. Install everything with one command

The GitHub repository is **private**, so the server can't download it yet. Pick one:
* **Easiest:** on GitHub open the repo → **Settings** → bottom of the page → **Change visibility** → **Public**.
* **Keep it private:** GitHub → your picture → **Settings** → **Developer settings** → **Personal access tokens** →
  **Fine-grained tokens** → **Generate new token**, give it read access ("Contents: Read") to this repo, and copy
  it (starts with `github_pat_`).

Then paste **one** of these into the server (put your Java Minecraft name after `OWNER=`):

Public repo:
```
curl -fsSL https://raw.githubusercontent.com/vylorq/anti-cheat/main/scripts/oracle-setup.sh | OWNER=YourName bash
```
Private repo (replace `TOKEN` twice):
```
curl -fsSL -H "Authorization: token TOKEN" https://raw.githubusercontent.com/vylorq/anti-cheat/main/scripts/oracle-setup.sh | OWNER=YourName GITHUB_TOKEN=TOKEN bash
```

It installs Java, opens the server firewall, **builds the mod on the server**, downloads Fabric, Fabric API,
Geyser and Floodgate, sets Bedrock login to Floodgate, makes you the owner and starts the server in the
background (it restarts by itself after a crash or reboot). The first run takes about 5–10 minutes.

* If it says **"The build failed"**, copy the red/error lines it shows and send them to Claude. After the fix,
  run the same command again.
* To **update** the mod later, run the same command again. Your world and settings are kept.

Useful commands on the server:
```
journalctl -u minecraft -f          # live server log (Ctrl+C to leave)
sudo systemctl restart minecraft    # restart
sudo systemctl stop minecraft       # stop
```
To type commands into the Minecraft console, use them in-game as the owner instead
(then follow "First-time setup" in `README.md` for the admin PIN, lobby and so on).

## 6. Friends join

* **Java:** Multiplayer → Add Server → address `YOUR_SERVER_IP`.
* **Bedrock (phone, Windows):** Servers → Add Server → address `YOUR_SERVER_IP`, port `19132`.
* **Console (Xbox, PlayStation, Switch):** see the "Consoles" part of `CROSSPLAY.md`.

## 7. Backups (so you never lose the world)

On the server:
```
cd ~/mc && tar czf ~/backup-$(date +%F).tar.gz world config
```
To keep a copy off the server: in Cloud Shell (after `exit` from the server) run
`scp -i ssh-key-*.key ubuntu@YOUR_SERVER_IP:~/backup-*.tar.gz .`, then gear/menu → **Download** and type the
file name.

## Using a PC instead

Everything above also works from a PC: in PowerShell run `ssh -i .\ssh-key-....key ubuntu@YOUR_SERVER_IP`
and paste the same one command. (If Windows says the key is "too open": right-click the key → Properties →
Security → Advanced → Disable inheritance → remove everyone except your own user.)
