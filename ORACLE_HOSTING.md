# Free server on Oracle Cloud (Java + Bedrock)

Oracle's **Always Free** tier gives you a server with up to **4 CPU cores and 24 GB RAM** at no cost, forever. That is
more than enough for you and your friends. You never pay as long as you **stay on the free tier** (don't click
"Upgrade to Pay As You Go").

Two things to know first:
* Oracle asks for a **card** when you sign up, only to check you're a real person. You may see a small temporary
  hold that is given back. You are not charged on the free tier.
* Oracle can **reclaim free servers that sit idle** for a long time (almost no CPU use for 7 days). If nobody plays
  for a week or more, back up your world (step 10) so you never lose it.

You need your PC for steps 5–9 (typing commands). Steps 1–4 work on your phone.

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

## 4. Build the mod jar on your PC

On your PC: download this repository (GitHub → branch `claude/anticheat-full` → Code → Download ZIP), install Java 21
(https://adoptium.net), then in the folder run `.\gradlew.bat build`. Your mod is `build\libs\anticheat-2.0.0.jar`.
(If the build shows errors, paste them to Claude to fix.)

## 5. Connect to the server from your PC

Open **PowerShell** on Windows, go to where the key file is (for example Downloads) and type
(use your key's file name and your server's IP):

```
cd Downloads
ssh -i .\ssh-key-2026-xx-xx.key ubuntu@YOUR_SERVER_IP
```
Type `yes` the first time. You're now "inside" the server. Everything in steps 6–8 is typed there.

> If Windows complains the key is "too open", right-click the key file → Properties → Security → Advanced →
> Disable inheritance → remove everyone except your own user.

## 6. Install Java and open the ports (server side)

Oracle's Ubuntu has its own firewall too. Copy these lines one at a time:

```
sudo apt update && sudo apt install -y openjdk-21-jre-headless screen
sudo iptables -I INPUT 6 -m state --state NEW -p tcp --dport 25565 -j ACCEPT
sudo iptables -I INPUT 6 -p udp --dport 19132 -j ACCEPT
sudo netfilter-persistent save
```

## 7. Set up the Minecraft server

```
mkdir -p ~/mc/mods && cd ~/mc
```

1. **Fabric server:** on https://fabricmc.net/use/server/ pick **Minecraft 1.21.4**, and copy the command it
   shows under "Download with curl" (it ends in `/server/jar`). Paste it here. Then rename the file:
   ```
   mv fabric-server*.jar server.jar
   ```
2. **Mods:** on Modrinth, open each mod's **Versions** tab, pick the **Fabric 1.21.4** build, right-click the
   download button → **Copy link**, then on the server type `wget -P mods "PASTED_LINK"`:
   * Fabric API: https://modrinth.com/mod/fabric-api
   * Geyser: https://modrinth.com/mod/geyser
   * Floodgate: https://modrinth.com/mod/floodgate
3. **Your anti-cheat mod:** in a **second** PowerShell window on your PC (not inside the server), send it up:
   ```
   scp -i .\ssh-key-2026-xx-xx.key C:\path\to\anti-cheat\build\libs\anticheat-2.0.0.jar ubuntu@YOUR_SERVER_IP:~/mc/mods/
   ```
4. Back in the server window, accept the EULA:
   ```
   echo "eula=true" > eula.txt
   ```

## 8. Start it (and keep it running when you log off)

```
screen -S mc
java -Xms4G -Xmx16G -jar server.jar nogui
```
Wait for **"Done"**. Then press **Ctrl+A**, then **D**. The server keeps running after you close PowerShell.
To come back to the console later: `screen -r mc`.

Now set the Bedrock login mode. Stop the server (in the console type `stop`), then:
```
nano ~/mc/config/Geyser-Fabric/config.yml
```
Find `auth-type:` and set it to `floodgate`. Save with **Ctrl+O**, **Enter**, **Ctrl+X**. Start the server again
(`screen -r mc`, then the `java ...` line).

Finally, in the server console, make yourself the owner:
```
ac setowner YourMinecraftName
```
(Then follow "First-time setup" in `README.md` for the admin PIN, lobby and so on.)

## 9. Friends join

* **Java:** Multiplayer → Add Server → address `YOUR_SERVER_IP`.
* **Bedrock (phone, Windows):** Servers → Add Server → address `YOUR_SERVER_IP`, port `19132`.
* **Console (Xbox, PlayStation, Switch):** see the "Consoles" part of `CROSSPLAY.md`.

## 10. Backups (so you never lose the world)

In the server window:
```
cd ~/mc && tar czf ~/backup-$(date +%F).tar.gz world config
```
Download it to your PC from PowerShell:
```
scp -i .\ssh-key-2026-xx-xx.key ubuntu@YOUR_SERVER_IP:~/backup-*.tar.gz .
```

## Optional: start automatically after a reboot

```
sudo nano /etc/systemd/system/minecraft.service
```
Paste:
```
[Unit]
Description=Minecraft server
After=network.target

[Service]
User=ubuntu
WorkingDirectory=/home/ubuntu/mc
ExecStart=/usr/bin/java -Xms4G -Xmx16G -jar server.jar nogui
Restart=on-failure

[Install]
WantedBy=multi-user.target
```
Then:
```
sudo systemctl enable --now minecraft
```
(If you use this, don't also start it with `screen`. Stop it with `sudo systemctl stop minecraft`,
and see the log with `journalctl -u minecraft -f`.)
