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

If **Auto-apply rates** is enabled and a rule matches, it looks for a *new* reward slot among the 36 shown. It opens that reward's editor and then its **Rarity & Weight** control at slot 12 (GLISTERING_MELON_SLICE). It only attempts to edit a rate dialog if it recognizes *one* EditBox and a CycleButton rarity selector, with an explicit confirm button. It STOPS instead of guessing on unsupported dialog widgets or layouts. Depending on the server version, manual Rarity & Weight editing may still be required. The mod cannot directly edit ExcellentCrates server configuration from the client.

## Safety

- Not tested on live production crates. Test with one expendable item first and verify the rarity/weight on the server.
- Does not avoid items added in prior sessions; duplicate prevention is limited to the current pass.
- If the Crate Rewards page already has 36 reward slots filled, auto-rate editing may stop because it cannot safely identify a new reward from the page.
- Press F8 if the interface changes unexpectedly. Check whether the mouse cursor is holding an item before closing the menu.
- This project is provided in an isolated GitHub branch without modifying the original application's default branch.
