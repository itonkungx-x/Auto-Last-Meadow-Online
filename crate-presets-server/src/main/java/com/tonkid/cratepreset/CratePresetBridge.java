package com.tonkid.cratepreset;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SuppressWarnings("deprecation")
public final class CratePresetBridge extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    private final Map<UUID,String> targets = new HashMap<>();
    private final Set<UUID> activeWrites = new LinkedHashSet<>();
    private final Map<UUID,List<Map<String,Object>>> stagedRules = new HashMap<>();
    private File storeFile;

    private static final int SIZE=45;
    private enum Mode { LIST, EDIT }
    private static final class Preset {
        final String id;
        String name;
        final ItemStack[] items=new ItemStack[SIZE];
        Preset(String id,String name){this.id=id;this.name=name;}
        int size(){int n=0;for(ItemStack i:items)if(i!=null&&!i.getType().isAir())n++;return n;}
    }
    private static final class Menu implements InventoryHolder {
        private Inventory inventory;
        final Mode mode;
        final String presetId;
        final int page;
        Menu(Mode mode,String presetId,int page){this.mode=mode;this.presetId=presetId;this.page=page;}
        @Override public Inventory getInventory(){return inventory;}
    }
    private static final class Rule {
        final String source, pattern, rarity;
        final double weight;
        Rule(String source,String pattern,String rarity,double weight){
            this.source=source;this.pattern=pattern;this.rarity=rarity;this.weight=weight;
        }
    }
    @Override public void onEnable(){
        saveDefaultConfig();
        storeFile=new File(getDataFolder(),"presets.yml");
        loadPresets();
        var cmd=getCommand("cratepreset");
        if(cmd!=null){cmd.setExecutor(this);cmd.setTabCompleter(this);}
        Bukkit.getPluginManager().registerEvents(this,this);
        getLogger().info("CratePresetBridge loaded "+presets.size()+" permanent presets");
    }
    private void loadPresets(){
        presets.clear();targets.clear();
        if(!storeFile.exists())return;
        YamlConfiguration yaml=YamlConfiguration.loadConfiguration(storeFile);
        ConfigurationSection root=yaml.getConfigurationSection("presets");
        if(root!=null)for(String id:root.getKeys(false)){
            String path="presets."+id+".";
            Preset p=new Preset(id,yaml.getString(path+"name",id));
            for(int i=0;i<SIZE;i++){
                ItemStack s=yaml.getItemStack(path+"item-"+i);
                if(s!=null&&!s.getType().isAir())p.items[i]=s.clone();
            }
            presets.put(id,p);
        }
        ConfigurationSection tg=yaml.getConfigurationSection("targets");
        if(tg!=null)for(String id:tg.getKeys(false))try{
            UUID uuid=UUID.fromString(id);
            String crate=tg.getString(id);
            if(crate!=null)targets.put(uuid,crate);
        }catch(IllegalArgumentException ignored){}
    }
    private void savePresets(){
        YamlConfiguration y=new YamlConfiguration();
        for(Preset p:presets.values()){
            String b="presets."+p.id+".";
            y.set(b+"name",p.name);
            for(int i=0;i<SIZE;i++)if(p.items[i]!=null)y.set(b+"item-"+i,p.items[i].clone());
        }
        for(var en:targets.entrySet())y.set("targets."+en.getKey(),en.getValue());
        try{
            Files.createDirectories(storeFile.toPath().getParent());
            File temp=new File(storeFile.getParentFile(),"presets.yml.tmp");
            y.save(temp);
            if(storeFile.exists()){
                File backup=new File(storeFile.getParentFile(),"presets.yml.bak");
                Files.copy(storeFile.toPath(),backup.toPath(),StandardCopyOption.REPLACE_EXISTING);
            }
            try{Files.move(temp.toPath(),storeFile.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(IOException unsupported){Files.move(temp.toPath(),storeFile.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        }catch(Exception e){getLogger().severe("Could not save presets: "+e);throw new IllegalStateException("Save failed",e);}
    }
    private static String lower(String s){return s.toLowerCase(Locale.ROOT);}
    private Preset resolve(String name){
        Preset byId=presets.get(lower(name));if(byId!=null)return byId;
        for(Preset p:presets.values())if(p.name.equalsIgnoreCase(name))return p;
        return null;
    }
    private static String normalize(String input){
        String small="ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        String ascii="abcdefghijklmnopqrstuvwxyz";
        String original=ChatColor.stripColor(input==null?"":input);
        StringBuilder b=new StringBuilder();
        for(char chr:original.toLowerCase(Locale.ROOT).toCharArray()){
            int n=small.indexOf(chr);
            b.append(n>=0&&n<ascii.length()?ascii.charAt(n):chr);
        }
        return b.toString();
    }
    private static ItemStack icon(Material type,String name,List<String> lore){
        ItemStack stack=new ItemStack(type);
        ItemMeta meta=stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        stack.setItemMeta(meta);
        return stack;
    }
    private static Material colorOf(String name){
        String s=normalize(name);
        if(s.contains("เหลือง")||s.contains("yellow"))return Material.YELLOW_SHULKER_BOX;
        if(s.contains("แดง")||s.contains("red"))return Material.RED_SHULKER_BOX;
        if(s.contains("เขียว")||s.contains("green"))return Material.GREEN_SHULKER_BOX;
        if(s.contains("ฟ้า")||s.contains("cyan"))return Material.CYAN_SHULKER_BOX;
        if(s.contains("น้ำเงิน")||s.contains("blue"))return Material.BLUE_SHULKER_BOX;
        if(s.contains("ม่วง")||s.contains("purple"))return Material.PURPLE_SHULKER_BOX;
        if(s.contains("ชมพู")||s.contains("pink"))return Material.PINK_SHULKER_BOX;
        return Material.CHEST;
    }
    private void list(Player p,int page){
        List<Preset> all=new ArrayList<>(presets.values());
        page=Math.max(0,Math.min(page,Math.max(0,(all.size()-1)/SIZE)));
        Menu menu=new Menu(Mode.LIST,null,page);
        Inventory inv=Bukkit.createInventory(menu,54,"§6กล่องรางวัลที่บันทึกไว้");
        menu.inventory=inv;
        for(int pos=0;pos<SIZE&&pos+page*SIZE<all.size();pos++){
            Preset preset=all.get(pos+page*SIZE);
            inv.setItem(pos,icon(colorOf(preset.name),"§e"+preset.name,Arrays.asList(
                "§7รหัส: §f"+preset.id,"§7จำนวน: §f"+preset.size()+" ชิ้น",
                "§aคลิกซ้าย: เปิดแก้ไขรายการ","§bคลิกขวา: นำไปเซ็ต Crate ที่เลือก")));
        }
        inv.setItem(45,icon(Material.ARROW,"§eหน้าก่อน",List.of()));
        inv.setItem(49,icon(Material.BOOK,"§fกล่องทั้งหมด: "+presets.size(),
            List.of("§7Target crate: "+targets.getOrDefault(p.getUniqueId(),"(ยังไม่เลือก)"),
                    "§7ตั้งด้วย /cratepreset target <crateId>",
                    "§7สร้างใหม่ด้วย /cratepreset create <ชื่อ>")));
        inv.setItem(53,icon(Material.ARROW,"§eหน้าถัดไป",List.of()));
        p.openInventory(inv);
    }
    private void editor(Player p,Preset preset){
        Menu menu=new Menu(Mode.EDIT,preset.id,0);
        Inventory inv=Bukkit.createInventory(menu,54,"§6กล่อง: §e"+preset.name);
        menu.inventory=inv;
        for(int i=0;i<SIZE;i++)if(preset.items[i]!=null)inv.setItem(i,preset.items[i].clone());
        inv.setItem(45,icon(Material.ARROW,"§eย้อนกลับ",List.of("§7บันทึกก่อนออก")));
        inv.setItem(49,icon(Material.LIME_DYE,"§aบันทึกกล่องนี้",List.of("§7เก็บไอเทม Custom พร้อมข้อมูลทั้งหมดไว้ถาวร")));
        inv.setItem(53,icon(Material.NETHER_STAR,"§bเซ็ตลง Crate",List.of(
                "§7ปลายทาง: "+targets.getOrDefault(p.getUniqueId(),"(ยังไม่เลือก)"),
                "§7ตั้งด้วย /cratepreset target <crateId>")));
        p.openInventory(inv);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args){
        if(!(sender instanceof Player p)){sender.sendMessage("This command is for in-game admins.");return true;}
        if(!p.hasPermission("cratepreset.admin")){p.sendMessage("§cไม่มีสิทธิ์ใช้ระบบนี้");return true;}
        if(args.length==0 || args[0].equalsIgnoreCase("list")){list(p,0);return true;}
        String sub=args[0].toLowerCase(Locale.ROOT);
        if(sub.equals("create")){
            if(args.length<2){p.sendMessage("§eใช้ /cratepreset create <ชื่อกล่อง>");return true;}
            String name=String.join(" ",Arrays.copyOfRange(args,1,args.length)).trim();
            if(name.length()>48){p.sendMessage("§cชื่อกล่องยาวเกิน 48 ตัวอักษร");return true;}
            if(resolve(name)!=null){p.sendMessage("§cมีชื่อกล่องนี้แล้ว");return true;}
            String id="box_"+UUID.randomUUID().toString().substring(0,8);
            Preset created=new Preset(id,name);presets.put(id,created);savePresets();editor(p,created);return true;
        }
        if(sub.equals("target")){
            if(args.length<2){p.sendMessage("§eใช้ /cratepreset target <crateId>");return true;}
            String crateId=lower(args[1]);
            if(!existsCrate(crateId)){p.sendMessage("§cไม่พบ Crate ID: "+crateId);return true;}
            targets.put(p.getUniqueId(),crateId);savePresets();
            p.sendMessage("§aเลือก Crate ปลายทางเป็น §f"+crateId);list(p,0);return true;
        }
        if(sub.equals("edit")){
            if(args.length<2){p.sendMessage("§eใช้ /cratepreset edit <id/ชื่อกล่อง>");return true;}
            Preset preset=resolve(String.join(" ",Arrays.copyOfRange(args,1,args.length)));
            if(preset==null){p.sendMessage("§cไม่พบกล่อง");return true;}
            editor(p,preset);return true;
        }
        if(sub.equals("apply")){
            if(args.length<2){p.sendMessage("§eใช้ /cratepreset apply <id/ชื่อกล่อง> [crateId]");return true;}
            Preset preset=resolve(args[1]);
            if(preset==null){p.sendMessage("§cไม่พบกล่อง "+args[1]);return true;}
            String crateId=args.length>=3?args[2]:targets.get(p.getUniqueId());
            if(crateId==null){p.sendMessage("§cตั้งปลายทางด้วย /cratepreset target <crateId> ก่อน");return true;}
            apply(p,preset,crateId);return true;
        }
        if(sub.equals("sync")){
            if(args.length<2){p.sendMessage("§eใช้ /cratepreset sync begin/add/commit/abort");return true;}
            String action=args[1].toLowerCase(Locale.ROOT);
            if(action.equals("begin")){
                stagedRules.put(p.getUniqueId(),new ArrayList<>());
                p.sendMessage("§7เริ่มรับกฎจากม็อด...");
                return true;
            }
            if(action.equals("abort")){
                stagedRules.remove(p.getUniqueId());
                p.sendMessage("§eยกเลิกการซิงก์กฎ");return true;
            }
            List<Map<String,Object>> staged=stagedRules.get(p.getUniqueId());
            if(staged==null){p.sendMessage("§cต้องเริ่มด้วย sync begin ก่อน");return true;}
            if(action.equals("add") && args.length==3){
                try{
                    String raw=new String(Base64.getUrlDecoder().decode(args[2]),StandardCharsets.UTF_8);
                    String[] fields=raw.split(String.valueOf((char)31),-1);
                    if(fields.length!=4 || fields[1].isBlank() || fields[1].length()>96 ||
                       fields[2].isBlank() || fields[2].length()>40 ||
                       !Set.of("NAME","LORE","MATERIAL").contains(fields[0]))
                        throw new IllegalArgumentException("Invalid rule fields");
                    double weight=Double.parseDouble(fields[3]);
                    if(!Double.isFinite(weight)||weight<0)throw new IllegalArgumentException("Invalid weight");
                    if(staged.size()>=100)throw new IllegalArgumentException("Max 100 rules");
                    staged.add(new LinkedHashMap<>(Map.of(
                        "source",fields[0],"pattern",fields[1],
                        "rarity",lower(fields[2]),"weight",weight)));
                }catch(Exception ex){
                    p.sendMessage("§cกฎไม่ถูกต้อง: "+ex.getMessage());
                    stagedRules.remove(p.getUniqueId());
                }
                return true;
            }
            if(action.equals("commit")){
                if(staged.isEmpty()){p.sendMessage("§cไม่มีกฎที่จะบันทึก");return true;}
                getConfig().set("rules",staged);saveConfig();
                stagedRules.remove(p.getUniqueId());
                p.sendMessage("§aซิงก์กฎจากม็อดแล้ว "+staged.size()+" รายการ");
                return true;
            }
            p.sendMessage("§cคำสั่ง sync ไม่ถูกต้อง");return true;
        }
        if(sub.equals("rule")){
            if(args.length==1){showRules(p);return true;}
            if(args.length==5 && args[1].equalsIgnoreCase("set")){
                if(!p.hasPermission("cratepreset.admin"))return true;
                String term=args[2], rarity=lower(args[3]);
                double weight;
                try{weight=Double.parseDouble(args[4]);}catch(Exception e){p.sendMessage("§cWeight ต้องเป็นตัวเลข");return true;}
                if(!Double.isFinite(weight)||weight<0){p.sendMessage("§cWeight ไม่ถูกต้อง");return true;}
                List<Map<?,?>> rules=getConfig().getMapList("rules");
                List<Map<String,Object>> replaced=new ArrayList<>();
                for(Map<?,?> item:rules){
                    String existing=String.valueOf(item.get("pattern"));
                    if(!existing.equalsIgnoreCase(term))replaced.add(copyMap(item));
                }
                replaced.add(0,Map.of("source","NAME","pattern",term,"rarity",rarity,"weight",weight));
                getConfig().set("rules",replaced);saveConfig();
                p.sendMessage("§aบันทึกกฎ "+term+" → "+rarity+" / "+weight);return true;
            }
            p.sendMessage("§eใช้ /cratepreset rule set <คำในชื่อ> <rarity> <weight>");
            return true;
        }
        p.sendMessage("§eคำสั่ง: /cratepreset, create, edit, target, apply, rule, sync");
        return true;
    }
    private static Map<String,Object> copyMap(Map<?,?> input){
        Map<String,Object> out=new LinkedHashMap<>();
        input.forEach((k,v)->out.put(String.valueOf(k),v));
        return out;
    }
    private void showRules(Player p){
        for(Rule r:readRules())p.sendMessage("§7"+r.source+":"+r.pattern+" §8→ §e"+r.rarity+" §f/ "+r.weight);
        p.sendMessage("§7ปรับด้วย /cratepreset rule set <คำในชื่อ> <rarity> <weight>");
    }
    @Override public List<String> onTabComplete(CommandSender s,Command cmd,String alias,String[] args){
        if(args.length==1)return List.of("list","create","target","edit","apply","rule","sync");
        if(args.length==2&&(args[0].equalsIgnoreCase("apply")||args[0].equalsIgnoreCase("edit")))return new ArrayList<>(presets.keySet());
        if(args.length==2&&args[0].equalsIgnoreCase("target"))return crateIds();
        if(args.length==2&&args[0].equalsIgnoreCase("rule"))return List.of("set");
        if(args.length==2&&args[0].equalsIgnoreCase("sync"))return List.of("begin","add","commit","abort");
        return List.of();
    }
    @EventHandler public void onClick(InventoryClickEvent event){
        if(!(event.getView().getTopInventory().getHolder() instanceof Menu menu))return;
        if(!(event.getWhoClicked() instanceof Player player))return;
        if(!player.hasPermission("cratepreset.admin")){event.setCancelled(true);return;}
        int slot=event.getRawSlot();
        if(menu.mode==Mode.LIST){
            if(slot<54){event.setCancelled(true);}
            if(slot>=0&&slot<SIZE){
                int index=slot+menu.page*SIZE;
                List<Preset> all=new ArrayList<>(presets.values());
                if(index>=all.size())return;
                Preset clicked=all.get(index);
                if(event.isRightClick()){
                    String target=targets.get(player.getUniqueId());
                    if(target==null){player.sendMessage("§cตั้งเป้าหมายด้วย /cratepreset target <crateId>");return;}
                    apply(player,clicked,target);
                }else editor(player,clicked);
            }else if(slot==45)list(player,Math.max(0,menu.page-1));
            else if(slot==53)list(player,menu.page+1);
            return;
        }
        Preset preset=presets.get(menu.presetId);
        if(preset==null){event.setCancelled(true);player.closeInventory();return;}
        if(slot>=54){
            // SHIFT+click from player inventory COPIES item (no item is consumed).
            if(event.isShiftClick()){
                event.setCancelled(true);
                ItemStack selected=event.getCurrentItem();
                if(selected==null||selected.getType().isAir())return;
                for(int i=0;i<SIZE;i++)if(menu.inventory.getItem(i)==null){
                    menu.inventory.setItem(i,selected.clone());break;
                }
            }
            return;
        }
        event.setCancelled(true);
        if(slot>=0&&slot<SIZE){
            ItemStack cursor=event.getCursor();
            if(cursor!=null&&!cursor.getType().isAir()){
                menu.inventory.setItem(slot,cursor.clone());
            }else menu.inventory.setItem(slot,null);
            return;
        }
        if(slot==49){saveEditor(player,preset,menu.inventory);return;}
        if(slot==45){saveEditor(player,preset,menu.inventory);list(player,0);return;}
        if(slot==53){
            saveEditor(player,preset,menu.inventory);
            String target=targets.get(player.getUniqueId());
            if(target==null)player.sendMessage("§cตั้ง Crate ด้วย /cratepreset target <crateId> ก่อน");
            else apply(player,preset,target);
        }
    }
    private void saveEditor(Player p,Preset preset,Inventory inv){
        for(int i=0;i<SIZE;i++){
            ItemStack item=inv.getItem(i);
            preset.items[i]=item==null||item.getType().isAir()?null:item.clone();
        }
        try{savePresets();p.sendMessage("§aบันทึกกล่อง §f"+preset.name+"§a แล้ว ("+preset.size()+" ชิ้น)");}
        catch(Exception e){p.sendMessage("§cบันทึกไม่สำเร็จ ดู console");}
    }
    @EventHandler public void onDrag(InventoryDragEvent e){
        if(e.getView().getTopInventory().getHolder() instanceof Menu menu){
            for(int index:e.getRawSlots())if(index<54){e.setCancelled(true);return;}
        }
    }
    @EventHandler public void onClose(InventoryCloseEvent e){
        // Deliberately do NOT auto-save on close. Only Save/Back persists.
        // Prevents accidental data loss caused by closing an unfinished editor.
    }
    private List<Rule> readRules(){
        List<Rule> rules=new ArrayList<>();
        for(Map<?,?> m:getConfig().getMapList("rules")){
            try{
                double weight=Double.parseDouble(String.valueOf(m.get("weight")));
                if(!Double.isFinite(weight)||weight<0)continue;
                rules.add(new Rule(
                    String.valueOf(m.get("source")),String.valueOf(m.get("pattern")),
                    lower(String.valueOf(m.get("rarity"))),weight));
            }catch(Exception ex){getLogger().warning("Ignored invalid rate rule "+m);}
        }
        return rules;
    }
    private Rule findRule(ItemStack item,List<Rule> rules){
        String name=item.hasItemMeta() && item.getItemMeta().hasDisplayName()?
            item.getItemMeta().getDisplayName():item.getType().name();
        String title=normalize(name);
        String material=normalize(item.getType().name());
        String lore="";
        if(item.hasItemMeta() && item.getItemMeta().hasLore())
            lore=normalize(String.join(" ",item.getItemMeta().getLore()));
        Rule fallback=null;
        for(Rule rule:rules){
            String term=normalize(rule.pattern);
            if(term.equals("*")){if(fallback==null)fallback=rule;continue;}
            String source=switch(rule.source.toUpperCase(Locale.ROOT)){
                case "LORE"->lore;
                case "MATERIAL"->material;
                default->title;
            };
            if(!term.isEmpty()&&source.contains(term))return rule;
        }
        return fallback;
    }
    private static Object invoke(Object target,String method,Class<?>[] types,Object...args)throws ReflectiveOperationException{
        return target.getClass().getMethod(method,types).invoke(target,args);
    }
    private Object ecPlugin(){
        Plugin plugin=Bukkit.getPluginManager().getPlugin("ExcellentCrates");
        return plugin!=null&&plugin.isEnabled()?plugin:null;
    }
    private boolean existsCrate(String id){
        try{
            Object ec=ecPlugin();if(ec==null)return false;
            Object mgr=invoke(ec,"getCrateManager",new Class<?>[]{});
            return invoke(mgr,"getCrateById",new Class<?>[]{String.class},lower(id))!=null;
        }catch(Exception e){getLogger().warning("Cannot check crate id: "+e);return false;}
    }
    private List<String> crateIds(){
        try{
            Object ec=ecPlugin();if(ec==null)return List.of();
            Object mgr=invoke(ec,"getCrateManager",new Class<?>[]{});
            @SuppressWarnings("unchecked") List<String> ids=(List<String>)invoke(mgr,"getCrateIds",new Class<?>[]{});
            return ids;
        }catch(Exception ignored){return List.of();}
    }
    private void apply(Player p,Preset preset,String crateId){
        if(!activeWrites.add(p.getUniqueId())){p.sendMessage("§cกำลังเซ็ตกล่องอื่นอยู่");return;}
        try{
            List<Rule> rules=readRules();
            if(preset.size()==0){p.sendMessage("§cกล่องยังไม่มีไอเทม");return;}
            Object ec=ecPlugin();
            if(ec==null){p.sendMessage("§cExcellentCrates ยังไม่พร้อมใช้งาน");return;}
            Object manager=invoke(ec,"getCrateManager",new Class<?>[]{});
            Object crate=invoke(manager,"getCrateById",new Class<?>[]{String.class},lower(crateId));
            if(crate==null){p.sendMessage("§cไม่พบ Crate ID: "+crateId);return;}
            ClassLoader loader=ec.getClass().getClassLoader();
            Class<?> crateType=loader.loadClass("su.nightexpress.excellentcrates.crate.impl.Crate");
            Class<?> rarityType=loader.loadClass("su.nightexpress.excellentcrates.crate.impl.Rarity");
            Class<?> rewardType=loader.loadClass("su.nightexpress.excellentcrates.api.crate.RewardType");
            Class<?> rewardClass=loader.loadClass("su.nightexpress.excellentcrates.api.crate.Reward");
            Class<?> adaptClass=loader.loadClass("su.nightexpress.nightcore.bridge.item.AdaptedItem");
            Class<?> typeClass=loader.loadClass("su.nightexpress.excellentcrates.CratesPlugin");
            Object itemEnum=Enum.valueOf((Class)rewardType,"ITEM");
            Class<?> factory=loader.loadClass("su.nightexpress.excellentcrates.crate.reward.RewardFactory");
            Class<?> helper=loader.loadClass("su.nightexpress.excellentcrates.util.ItemHelper");
            Method create=factory.getMethod("create",typeClass,crateType,String.class,rarityType,rewardType);
            Method vanilla=helper.getMethod("vanilla",ItemStack.class);
            Method setItems=loader.loadClass("su.nightexpress.excellentcrates.crate.reward.impl.ItemReward")
                .getMethod("setItems",List.class);
            Method add=crateType.getMethod("addReward",rewardClass);
            Method remove=crateType.getMethod("removeReward",rewardClass);
            Method get=crateType.getMethod("getReward",String.class);
            Method dirty=crateType.getMethod("markDirty");
            Method save=crateType.getMethod("saveForce");
            Method setRarity=rewardClass.getMethod("setRarity",rarityType);
            Method setWeight=rewardClass.getMethod("setWeight",double.class);
            Method getRewards=crateType.getMethod("getRewards");
            Map<Integer,Object> planned=new LinkedHashMap<>();
            Map<Integer,Rule> selected=new LinkedHashMap<>();
            int total=0;
            for(int i=0;i<SIZE;i++){
                ItemStack item=preset.items[i];
                if(item==null||item.getType().isAir())continue;
                Rule match=findRule(item,rules);
                if(match==null)throw new IllegalStateException("No rule for slot "+i);
                String rarityId=match.rarity;
                Object rarity=invoke(manager,"getRarity",new Class<?>[]{String.class},rarityId);
                if(rarity==null && rarityId.equals("mythical"))
                    rarity=invoke(manager,"getRarity",new Class<?>[]{String.class},"mythic");
                if(rarity==null)throw new IllegalStateException("Rarity '"+rarityId+"' missing; available: "+invoke(manager,"getRarityIds",new Class<?>[]{}));
                planned.put(i,rarity);selected.put(i,match);total++;
            }
            // Backup the actual crate YAML BEFORE writing, when API provides its path.
            Object location=crateType.getMethod("getPath").invoke(crate);
            if(location instanceof java.nio.file.Path crateFile && Files.isRegularFile(crateFile)){
                File backupDir=new File(getDataFolder(),"crate-backups");
                if(!backupDir.exists()&&!backupDir.mkdirs())throw new IOException("Cannot make backup folder");
                Files.copy(crateFile,new File(backupDir,crateId+"-"+Instant.now().toEpochMilli()+".yml").toPath());
            }
            String prefix="car_"+preset.id+"_";
            @SuppressWarnings("unchecked") List<Object> existing=new ArrayList<>((java.util.Collection<Object>)getRewards.invoke(crate));
            // Remove only stale rewards generated previously by this same preset.
            for(Object reward:existing){
                String id=(String)invoke(reward,"getId",new Class<?>[]{});
                if(!id.startsWith(prefix))continue;
                try{
                    int idx=Integer.parseInt(id.substring(prefix.length()));
                    if(planned.containsKey(idx))continue;
                    remove.invoke(crate,reward);
                }catch(NumberFormatException ignored){}
            }
            int created=0,updated=0;
            for(var pair:planned.entrySet()){
                int i=pair.getKey();
                ItemStack item=preset.items[i].clone();
                String id=prefix+i;
                Object reward=get.invoke(crate,id);
                if(reward!=null && !loader.loadClass("su.nightexpress.excellentcrates.crate.reward.impl.ItemReward").isInstance(reward))
                    throw new IllegalStateException("Reward ID collides with non-item reward: "+id);
                if(reward==null){
                    reward=create.invoke(null,ec,crate,id,pair.getValue(),itemEnum);
                    add.invoke(crate,reward);created++;
                }else updated++;
                Object adapted=vanilla.invoke(null,item); // Full vanilla/NBT copy, not provider/item ID.
                setItems.invoke(reward,List.of(adapted));
                setRarity.invoke(reward,pair.getValue());
                setWeight.invoke(reward,selected.get(i).weight);
            }
            dirty.invoke(crate);
            save.invoke(crate);
            p.sendMessage("§aเซ็ต §f"+preset.name+" §a→ §f"+crateId+
                " §aสำเร็จ: "+total+" ไอเทม (ใหม่ "+created+" / อัปเดต "+updated+")");
            p.sendMessage("§7ของเซฟเก็บ ItemStack แยกจาก Inventory และใช้ Reward ID เดิมเมื่อเซ็ตซ้ำ");
            getLogger().info(p.getName()+" applied preset "+preset.name+" to "+crateId+": "+total+" slots");
        }catch(Exception e){
            p.sendMessage("§cเซ็ตไม่สำเร็จ: "+e.getMessage()+" §7ดู Console / หลีกเลี่ยงการกดซ้ำ");
            getLogger().severe("Failed to apply "+preset.name+" to "+crateId+": "+e);
            e.printStackTrace();
        }finally{activeWrites.remove(p.getUniqueId());}
    }
}
