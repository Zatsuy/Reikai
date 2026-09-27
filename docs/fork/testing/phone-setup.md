# Connect your phone for agent testing

Once your phone is connected, agents can install a build, open it, take screenshots and read
crash logs themselves, instead of asking you to test by hand. It takes about five minutes, once.
Your phone and the computer must be on the same Wi-Fi network.

## 1. Turn on developer options (once)

1. On the phone open **Settings** → **About phone**.
2. Tap **Build number** seven times. **Expect:** "You are now a developer".
   (On Samsung: **Settings** → **About phone** → **Software information** → **Build number**.)

## 2. Pair over Wi-Fi

1. Open **Settings** → **System** → **Developer options** (Samsung: **Settings** → **Developer
   options**) and switch on **Wireless debugging**. Accept the prompt for your Wi-Fi network.
2. Tap **Wireless debugging** itself, then **Pair device with pairing code**.
   **Expect:** a six-digit code and an address like `192.168.1.23:37123`.
3. In Claude Code, tell the agent: *"pair my phone: 192.168.1.23:37123 code 123456"* (your values).
   The agent runs `adb pair` for you. The code only works for a minute and only for pairing, so it
   is safe to type in chat.
4. Back on the **Wireless debugging** screen, note the **IP address & Port** at the top (a
   different port from the pairing one) and give it to the agent: *"connect 192.168.1.23:41234"*.
   **Expect:** the agent reports the phone as connected.

**If not:** send the agent a screenshot of the Wireless debugging screen.

## Later sessions

Wireless debugging switches itself off after a while or when Wi-Fi changes. When the session-start
line says *Phone over adb: not connected*, switch it on again and give the agent the new
**IP address & Port** (step 2.4); pairing is not needed again.

## USB instead

Plug the phone in with a cable, switch on **USB debugging** in **Developer options**, and accept
the *Allow USB debugging?* prompt on the phone (tick *Always allow from this computer*).
