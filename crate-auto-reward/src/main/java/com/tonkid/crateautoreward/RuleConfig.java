package com.tonkid.crateautoreward;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class RuleConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("crateautoreward-rules.json");

    public static final class Rule {
        public String source="NAME";
        public String contains="STAFF";
        public String rarity="MYTHIC";
        public String weight="0.01";

        public Rule() {}
        public Rule(String source,String contains,String rarity,String weight) {
            this.source=source;this.contains=contains;this.rarity=rarity;this.weight=weight;
        }
        public Rule copy() { return new Rule(source,contains,rarity,weight); }
        public String info(){return source+" : "+contains+"  >  "+rarity+" / "+weight;}
    }
    public List<Rule> rules = new ArrayList<>();
    public boolean autoApplyRates = true;

    public static RuleConfig defaults() {
        RuleConfig config=new RuleConfig();
        config.rules.add(new Rule("NAME","STAFF","MYTHICAL","0.01"));
        config.rules.add(new Rule("NAME","SWORD","RARE","0.10"));
        config.rules.add(new Rule("NAME","HELMET","RARE","0.01"));
        config.rules.add(new Rule("NAME","CHESTPLATE","RARE","0.01"));
        config.rules.add(new Rule("NAME","LEGGINGS","RARE","0.01"));
        config.rules.add(new Rule("NAME","BOOTS","RARE","0.01"));
        config.rules.add(new Rule("NAME","BOW","EPIC","0.05"));
        config.rules.add(new Rule("NAME","*","COMMON","10"));
        return config;
    }
    public static RuleConfig load() {
        if (!Files.exists(FILE)) {
            RuleConfig defaults=defaults();defaults.save();return defaults;
        }
        try {
            RuleConfig data=GSON.fromJson(Files.readString(FILE,StandardCharsets.UTF_8),RuleConfig.class);
            if (data==null || data.rules==null) return defaults();
            data.rules.removeIf(rule->rule==null);
            return data;
        } catch (Exception e) {
            System.err.println("[CrateAutoReward] Invalid config: "+e.getMessage());
            return defaults();
        }
    }
    public void save(){
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE,GSON.toJson(this),StandardCharsets.UTF_8);
        } catch(IOException e) {
            System.err.println("[CrateAutoReward] Failed to save rules: "+e);
        }
    }
    public Rule match(ItemStack stack) {
        if(stack.isEmpty()) return null;
        String name=normalize(stack.getHoverName().getString());
        String material=normalize(stack.getItem().toString());
        StringBuilder lore=new StringBuilder();
        ItemLore list=stack.get(DataComponents.LORE);
        if(list!=null) for(var component:list.lines()) lore.append(normalize(component.getString())).append(" ");
        Rule fallback=null;
        for(Rule rule:rules){
            if(rule.contains==null || rule.source==null || rule.rarity==null || rule.weight==null) continue;
            if(normalize(rule.contains).equals("*")){if(fallback==null)fallback=rule;continue;}
            String input=switch(rule.source.toUpperCase(Locale.ROOT)){
                case "LORE" -> lore.toString();
                case "MATERIAL" -> material;
                default -> name;
            };
            String term=normalize(rule.contains);
            if(!term.isEmpty() && input.contains(term)) return rule.copy();
        }
        return fallback==null?null:fallback.copy();
    }
    // Small-caps in custom item names are not ordinary ASCII uppercase/lowercase.
    // Unicode NFKC alone does not fold all of these glyphs.
    public static String normalize(String value){
        if(value==null) return "";
        String from="ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
        String to="abcdefghijklmnopqrstuvwxyz";
        String lower=value.toLowerCase(Locale.ROOT).replaceAll("§[0-9a-fk-or]","");
        StringBuilder output=new StringBuilder();
        for(int i=0;i<lower.length();i++){
            char c=lower.charAt(i);
            int idx=from.indexOf(c);
            if(idx>=0 && idx<to.length()) output.append(to.charAt(idx));
            else output.append(c);
        }
        return output.toString().trim();
    }
    public static boolean validWeight(String text){
        try {
            double weight=Double.parseDouble(text);
            return Double.isFinite(weight) && weight>=0 && text.length()<=6;
        } catch(Exception e){return false;}
    }
}
