# Crate Auto Reward — Fabric 26.3

A client-side Minecraft mod for the ExcellentCrates reward creation GUI. Uses Minecraft 26.3, Fabric API and Java 25.

## Keybinds

You can remap the keys from **F7 → ตั้งปุ่มลัด**; this opens Minecraft's built-in key binding editor, so it works on laptops without needing Fn/F-keys. Rebinding persists in Minecraft options.

Minecraft **Options -> Controls -> Key Binds** -> Miscellaneous:
- **F6** Start/stop one pass over distinct inventory item stacks.
- **F7** Open the rule editor (open it outside the crate GUI).
- **F8** Emergency stop.

All keys are standard remappable Minecraft key bindings, saved in Minecraft's normal options.

## In-game rules

Open F7. Pick `NAME` (display name), `LORE`, or `MATERIAL`. Enter keyword, exact rarity ID and decimal weight, then Add / Update. Click a row to edit it. Rules are evaluated from top to bottom; `*` acts as a fallback. Small-caps glyphs such as `ꜱᴛᴀꜰꜰ` are normalized for matching `STAFF`.

Settings save automatically to `config/crateautoreward-rules.json`.

## Crate operation

Open **Crate Rewards**, press F6. For each unique inventory item, the mod picks it up, clicks the Anvil in slot 42, then clicks **Item Reward** on the creation dialog.

If **Auto-apply rates** is enabled and a rule matches, it looks for a *new* reward slot among the 36 shown. It opens that reward's editor and then its **Rarity & Weight** control at slot 12 (GLISTERING_MELON_SLICE). It edits the **Weight** field then cycles the **Rarity:** button (including ordinary Buttons displaying Small Caps) until the requested rarity is visible, and presses the **✔ Ok** confirmation. `MYTHICAL` is recognized as the server's `MYTHIC` label. It stops rather than clicking unspecified controls, and verifies the server-returned lore before claiming success. It STOPS instead of guessing on unsupported dialog widgets or layouts. Depending on the server version, manual Rarity & Weight editing may still be required. The mod cannot directly edit ExcellentCrates server configuration from the client.

## Safety

- Not tested on live production crates. Test with one expendable item first and verify the rarity/weight on the server.
- Does not avoid items added in prior sessions; duplicate prevention is limited to the current pass.
- If the Crate Rewards page already has 36 reward slots filled, auto-rate editing may stop because it cannot safely identify a new reward from the page.
- Press F8 if the interface changes unexpectedly. Check whether the mouse cursor is holding an item before closing the menu.
- This project is provided in an isolated GitHub branch without modifying the original application's default branch.


## Saved crate presets (v0.4.0)

To retain the original Minecraft custom item data across sessions even when the items are no longer in the player's inventory, install **CratePresetBridge-26.3** on the Paper/Purpur server alongside ExcellentCrates 7.x. The client mod alone cannot manufacture item stacks or change server rewards from a saved snapshot.

In-game server admin commands:
- `/cratepreset`: open saved preset library with colored shulker icons.
- `/cratepreset create สีเหลือง`: create and open the yellow preset editor.
- Shift-click each inventory item to **copy** it into the preset slots without consuming the source.
- Click **บันทึกกล่องนี้**. Server persists exact Bukkit ItemStack data under `plugins/CratePresetBridge/presets.yml`, keeping a `.bak` backup on each save.
- `/cratepreset target <crateId>`: select the ExcellentCrates destination.
- In saved preset library, **right-click** a box to apply its captured items and Rarity/Weight to the target crate. Reapplying updates rewards with stable IDs instead of inserting duplicates.
- From F7 mod rules, click **ซิงก์กฎไปเซิร์ฟเวอร์** (server admin access required). Rules sync atomically on completion.

In the client mod, press remappable **K** to open saved presets, or F7 then **กล่องที่เซฟ**. The plugin requires the `cratepreset.admin` permission (op by default). Rarity IDs must be defined in ExcellentCrates, e.g. `mythical` (or server alias `mythic`), `legendary`, `epic`, `rare` and `common`. For any compatibility changes to external custom-item plugins, re-test a stored preset first; the archive preserves item data, not third-party plugin executable behavior.
