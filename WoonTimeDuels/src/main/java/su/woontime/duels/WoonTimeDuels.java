package su.woontime.duels;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import java.io.File;
import java.io.IOException;
import java.util.*;

public final class WoonTimeDuels extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID,Integer> ratings = new HashMap<>();
    private final Map<UUID,Integer> emeralds = new HashMap<>();
    private final Map<UUID,UUID> invitations = new HashMap<>();
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Map<UUID,UUID> opponents = new HashMap<>();
    private final Map<UUID,Location> returns = new HashMap<>();
    private final Map<UUID,ItemStack[]> savedItems = new HashMap<>();
    private final Map<UUID,ItemStack[]> savedArmor = new HashMap<>();
    private final Map<UUID,Long> matchStart = new HashMap<>();
    private final Map<UUID,String> matchKit = new HashMap<>();
    private final Map<UUID,String> playerArena = new HashMap<>();
    private final Set<String> busyArenas = new HashSet<>();
    private final Map<UUID,String> queueKits = new HashMap<>();
    private final Map<UUID,Long> queueStarted = new HashMap<>();
    private final Map<UUID,BossBar> queueBars = new HashMap<>();
    private final Map<UUID,String> editingKits = new HashMap<>();
    private final Map<UUID,ItemStack[]> editorSavedItems = new HashMap<>();
    private final Map<UUID,ItemStack[]> editorSavedArmor = new HashMap<>();
    private final Map<UUID,ItemStack> editorSavedOffhand = new HashMap<>();
    private final YamlConfiguration personalKits = new YamlConfiguration();
    private File personalKitsFile;
    private File dataFile;
    private YamlConfiguration data;

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("duel")).setExecutor(this);
        Objects.requireNonNull(getCommand("dueladmin")).setExecutor(this);
        personalKitsFile = new File(getDataFolder(), "personal-kits.yml");
        try { if (!personalKitsFile.exists()) { getDataFolder().mkdirs(); personalKitsFile.createNewFile(); } personalKits.load(personalKitsFile); } catch (Exception ex) { getLogger().warning("Could not load personal kits: " + ex.getMessage()); }
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        if (getServer().getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new netPlaceholderExpansion(this).register();
        }
        getLogger().info("WoonTimeDuels enabled.");
    }

    @Override public void onDisable() {
        for (UUID id : new HashSet<>(opponents.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) endMatch(p, false, "Сервер перезапускается.");
        }
        saveData();
    }

    private int rating(UUID id) { return ratings.computeIfAbsent(id, k -> data.getInt(k + ".rating", getConfig().getInt("settings.starting-rating", 1000))); }
    private int emeralds(UUID id) { return emeralds.computeIfAbsent(id, k -> data.getInt(k + ".emeralds", 0)); }
    private void saveData() {
        for (UUID id : ratings.keySet()) data.set(id + ".rating", ratings.get(id));
        for (UUID id : emeralds.keySet()) data.set(id + ".emeralds", emeralds.get(id));
        try { data.save(dataFile); } catch (IOException e) { getLogger().severe("Could not save players.yml: " + e.getMessage()); }
    }
    private void msg(CommandSender s, String text) { s.sendMessage(ChatColor.LIGHT_PURPLE + "[WoonTime] " + ChatColor.WHITE + text); }
    private Location loc(String path) {
        String worldName = getConfig().getString(path + ".world");
        World w = worldName == null ? null : Bukkit.getWorld(worldName);
        if (w == null) return null;
        return new Location(w, getConfig().getDouble(path + ".x"), getConfig().getDouble(path + ".y"),
            getConfig().getDouble(path + ".z"), (float)getConfig().getDouble(path + ".yaw"), (float)getConfig().getDouble(path + ".pitch"));
    }
    private List<String> kits() {
        ConfigurationSection section = getConfig().getConfigurationSection("kits");
        return section == null ? List.of("default") : new ArrayList<>(section.getKeys(false));
    }
    private static final String MENU_TITLE = ChatColor.LIGHT_PURPLE + "✦ WoonTime • Дуэли";
    private static final String EDITOR_TITLE = ChatColor.LIGHT_PURPLE + "Редактор кита: ";

    private void openKits(Player p) {
        List<String> names = kits();
        Inventory inv = Bukkit.createInventory(null, 54, MENU_TITLE);
        ItemStack pane = new ItemStack(Material.PINK_STAINED_GLASS_PANE);
        ItemMeta paneMeta = pane.getItemMeta(); paneMeta.setDisplayName(ChatColor.LIGHT_PURPLE + "woontime.su"); pane.setItemMeta(paneMeta);
        for (int slot=0; slot<54; slot++) if (slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8) inv.setItem(slot, pane);
        int[] slots = {10,11,12,13,14,15,16,19,20,21,22,23,24,25,28,29,30,31,32,33,34};
        for (int i=0;i<names.size() && i<slots.length;i++) {
            String name=names.get(i);
            ItemStack icon = new ItemStack(kitIcon(name));
            ItemMeta meta=icon.getItemMeta();
            meta.setDisplayName(ChatColor.LIGHT_PURPLE + name);
            int waiting=0; for(String queued:queueKits.values()) if(queued.equalsIgnoreCase(name)) waiting++;
            meta.setLore(List.of(ChatColor.GRAY+"В очереди на кит: "+ChatColor.LIGHT_PURPLE+waiting,
                ChatColor.WHITE+"ЛКМ — Выбрать кит",
                ChatColor.WHITE+"ПКМ — Редактировать кит"));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ADDITIONAL_TOOLTIP, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
            icon.setItemMeta(meta); inv.setItem(slots[i],icon);
        }
        ItemStack info=new ItemStack(Material.PAPER); ItemMeta im=info.getItemMeta();
        im.setDisplayName(ChatColor.LIGHT_PURPLE+"WoonTime.su");
        im.setLore(List.of(ChatColor.GRAY+"Выбери набор для поиска дуэли",ChatColor.GRAY+"Пока ждёшь, ПКМ по киту — выйти из очереди"));
        info.setItemMeta(im); inv.setItem(49,info);
        p.openInventory(inv);
    }
    private Material kitIcon(String name) {
        List<String> items=getConfig().getStringList("kits."+name+".items");
        for(String raw:items){Material m=Material.matchMaterial(raw.split(":")[0]);if(m!=null && m.name().endsWith("_SWORD"))return m;}
        return Material.IRON_SWORD;
    }
    private void refreshMenus() {
        for(Player p:Bukkit.getOnlinePlayers()) if(p.getOpenInventory().getTitle().equals(MENU_TITLE)) openKits(p);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title=e.getView().getTitle();
        if (title.equals(MENU_TITLE)) {
            e.setCancelled(true);
            ItemStack item=e.getCurrentItem();
            if(item==null || !item.hasItemMeta() || item.getItemMeta().getDisplayName()==null) return;
            String name=ChatColor.stripColor(item.getItemMeta().getDisplayName());
            if(name.equalsIgnoreCase("woontime.su") || name.startsWith("WoonTime")) return;
            if(e.getClick().isRightClick()) {
                if(queue.contains(p.getUniqueId())) { leaveQueue(p); openKits(p); return; }
                openKitEditor(p,name); return;
            }
            if(e.getClick().isLeftClick()) { p.closeInventory(); joinQueue(p,name); }
            return;
        }
        if(title.startsWith(EDITOR_TITLE)) {
            // Allow moving items around in the editor; clicks into the player's own inventory are also allowed.
            return;
        }
    }
    private void openKitEditor(Player p,String kit) {
        UUID id=p.getUniqueId();
        editorSavedItems.put(id,p.getInventory().getContents().clone());
        editorSavedArmor.put(id,p.getInventory().getArmorContents().clone());
        editorSavedOffhand.put(id,p.getInventory().getItemInOffHand().clone());
        editingKits.put(id,kit);
        Inventory edit=Bukkit.createInventory(null,54,EDITOR_TITLE+kit);
        List<String> saved=personalKits.getStringList(id+"."+kit+".layout");
        if(!saved.isEmpty()) {
            for(String raw:saved) { String[] parts=raw.split(":",3); if(parts.length<3)continue;
                int slot=parseInt(parts[0],-1); Material mat=Material.matchMaterial(parts[1]);
                if(slot>=0&&slot<54&&mat!=null)edit.setItem(slot,new ItemStack(mat,Math.max(1,parseInt(parts[2],1))));
            }
        } else {
            List<ItemStack> items=defaultKitItems(kit);
            for(int i=0;i<Math.min(36,items.size());i++)edit.setItem(i,items.get(i).clone());
        }
        ItemStack pane=new ItemStack(Material.PINK_STAINED_GLASS_PANE);
        ItemMeta meta=pane.getItemMeta();meta.setDisplayName(ChatColor.LIGHT_PURPLE+"woontime.su");pane.setItemMeta(meta);
        for(int slot=50;slot<54;slot++)edit.setItem(slot,pane);
        p.sendMessage(ChatColor.LIGHT_PURPLE+"Редактор: слоты 45–48 — броня, 49 — вторая рука. Закрой меню для сохранения.");
        p.openInventory(edit);
    }
    private List<ItemStack> defaultKitItems(String kit) {
        List<ItemStack> result=new ArrayList<>();
        for(String raw:getConfig().getStringList("kits."+kit+".items")) {
            String[] parts=raw.split(":"); Material m=Material.matchMaterial(parts[0]); if(m==null)continue;
            int amount=parts.length>1?parseInt(parts[1],1):1; result.add(new ItemStack(m,Math.max(1,Math.min(m.getMaxStackSize(),amount))));
        }
        return result;
    }
    private List<ItemStack> personalKitItems(UUID id,String kit) {
        String path=id+"."+kit+".items";
        if(!personalKits.contains(path)) return null;
        List<ItemStack> result=new ArrayList<>();
        for(String raw:personalKits.getStringList(path)) {
            String[] parts=raw.split(":"); Material m=Material.matchMaterial(parts[0]); if(m!=null) result.add(new ItemStack(m,Math.max(1,parseInt(parts.length>1?parts[1]:"1",1))));
        }
        return result;
    }
    @EventHandler public void onInventoryClose(InventoryCloseEvent e) {
        if(!(e.getPlayer() instanceof Player p) || !e.getView().getTitle().startsWith(EDITOR_TITLE))return;
        UUID id=p.getUniqueId();String kit=editingKits.remove(id);if(kit==null)return;
        List<String> layout=new ArrayList<>();
        for(int slot=0;slot<54;slot++){ItemStack it=e.getInventory().getItem(slot);if(it!=null&&it.getType()!=Material.AIR&&it.getType()!=Material.PINK_STAINED_GLASS_PANE)layout.add(slot+":"+it.getType().name()+":"+it.getAmount());}
        personalKits.set(id+"."+kit+".layout",layout);
        try{personalKits.save(personalKitsFile);}catch(IOException ex){getLogger().warning("Could not save personal kit: "+ex.getMessage());}
        p.getInventory().clear();
        ItemStack[] contents=editorSavedItems.remove(id);if(contents!=null)p.getInventory().setContents(contents);
        ItemStack[] armor=editorSavedArmor.remove(id);if(armor!=null)p.getInventory().setArmorContents(armor);
        ItemStack offhand=editorSavedOffhand.remove(id);if(offhand!=null)p.getInventory().setItemInOffHand(offhand);
        msg(p,"Твой кит "+kit+" сохранён.");
    }
    private void joinQueue(Player p, String kit) {
        if (opponents.containsKey(p.getUniqueId())) { msg(p,"Ты уже в дуэли."); return; }
        if (queue.contains(p.getUniqueId())) { msg(p,"Ты уже ищешь соперника. /duel cancel — отменить."); return; }
        UUID match=null;
        for(UUID id: new ArrayList<>(queue)) {
            Player other=Bukkit.getPlayer(id);
            if(other!=null && !id.equals(p.getUniqueId())) { match=id; break; }
        }
        if(match==null) {
            queue.add(p.getUniqueId()); queueKits.put(p.getUniqueId(),kit); queueStarted.put(p.getUniqueId(),System.currentTimeMillis());
            p.setMetadata("wt-duel-kit",new org.bukkit.metadata.FixedMetadataValue(this,kit));
            BossBar bar=Bukkit.createBossBar(ChatColor.LIGHT_PURPLE+"В ожидании • 0:00",BarColor.PINK,BarStyle.SOLID);
            bar.addPlayer(p); bar.setVisible(true); queueBars.put(p.getUniqueId(),bar);
            new BukkitRunnable(){ public void run(){ if(!queue.contains(p.getUniqueId())||!p.isOnline()){cancel();return;}
                long secs=(System.currentTimeMillis()-queueStarted.getOrDefault(p.getUniqueId(),System.currentTimeMillis()))/1000;
                bar.setTitle(ChatColor.LIGHT_PURPLE+"В ожидании • "+(secs/60)+":"+String.format("%02d",secs%60));
            }}.runTaskTimer(this,0L,20L);
            msg(p,"Ищем соперника для набора "+kit+"... ПКМ по киту в меню — выйти из очереди."); refreshMenus(); return;
        }
        queue.remove(match); clearQueueState(match);
        Player other=Bukkit.getPlayer(match);
        String otherKit=queueKits.getOrDefault(match, other!=null && other.hasMetadata("wt-duel-kit") ? other.getMetadata("wt-duel-kit").get(0).asString() : kit);
        if(other!=null) startMatch(p,other,kit.equals(otherKit)?kit:otherKit); refreshMenus();
    }
    private void clearQueueState(UUID id) {
        queueKits.remove(id); queueStarted.remove(id);
        BossBar bar=queueBars.remove(id); if(bar!=null){bar.removeAll();bar.setVisible(false);}
    }
    private void leaveQueue(Player p) {
        UUID id=p.getUniqueId(); queue.remove(id); clearQueueState(id);
        msg(p,"Ты вышел из очереди."); refreshMenus();
    }
    private String findFreeArena() {
        ConfigurationSection arenas = getConfig().getConfigurationSection("arenas");
        if (arenas == null) return null;
        for (String name : arenas.getKeys(false)) {
            if (busyArenas.contains(name)) continue;
            Location one = loc("arenas." + name + ".spawn1");
            Location two = loc("arenas." + name + ".spawn2");
            if (one != null && two != null && one.getWorld().equals(two.getWorld())) return name;
        }
        return null;
    }
    private void startMatch(Player a, Player b, String kit) {
        String arena = findFreeArena();
        if (arena == null) {
            msg(a, "Сейчас все арены заняты или не настроены. Попробуйте позже.");
            msg(b, "Сейчас все арены заняты или не настроены. Попробуйте позже.");
            return;
        }
        Location one=loc("arenas."+arena+".spawn1"), two=loc("arenas."+arena+".spawn2");
        if(one==null || two==null) { msg(a,"Арена "+arena+" не настроена: нужны оба спавна."); msg(b,"Арена "+arena+" не настроена."); return; }
        if(!one.getWorld().equals(two.getWorld())) { msg(a,"Спавны арены должны быть в одном мире."); return; }
        UUID x=a.getUniqueId(), y=b.getUniqueId();
        busyArenas.add(arena); playerArena.put(x,arena); playerArena.put(y,arena);
        opponents.put(x,y); opponents.put(y,x); matchStart.put(x,System.currentTimeMillis()); matchStart.put(y,System.currentTimeMillis()); matchKit.put(x,kit); matchKit.put(y,kit);
        returns.put(x,a.getLocation()); returns.put(y,b.getLocation());
        savedItems.put(x,a.getInventory().getContents()); savedItems.put(y,b.getInventory().getContents());
        savedArmor.put(x,a.getInventory().getArmorContents()); savedArmor.put(y,b.getInventory().getArmorContents());
        a.getInventory().clear(); b.getInventory().clear(); a.getInventory().setArmorContents(null); b.getInventory().setArmorContents(null);
        giveKit(a,kit); giveKit(b,kit);
        a.teleport(one); b.teleport(two);
        new BukkitRunnable(){ int n=getConfig().getInt("settings.countdown-seconds",5);
            public void run(){ if(!opponents.containsKey(x)||!opponents.containsKey(y)||!a.isOnline()||!b.isOnline()){cancel();return;}
                if(n>0){a.sendTitle(ChatColor.LIGHT_PURPLE+""+n,b.getName(),0,20,0);b.sendTitle(ChatColor.LIGHT_PURPLE+""+n,a.getName(),0,20,0); a.playSound(a.getLocation(),Sound.BLOCK_NOTE_BLOCK_PLING,1,1);b.playSound(b.getLocation(),Sound.BLOCK_NOTE_BLOCK_PLING,1,1);n--;}
                else {a.sendTitle(ChatColor.GREEN+"БОЙ!", "",0,25,5);b.sendTitle(ChatColor.GREEN+"БОЙ!", "",0,25,5);a.setNoDamageTicks(0);b.setNoDamageTicks(0);cancel();}
            }}.runTaskTimer(this,0L,20L);
    }
    private void giveKit(Player p,String kit) {
        List<String> layout=personalKits.getStringList(p.getUniqueId()+"."+kit+".layout");
        if(!layout.isEmpty()) {
            ItemStack[] armor=new ItemStack[4];
            for(String raw:layout) {
                String[] parts=raw.split(":",3);if(parts.length<3)continue;
                int slot=parseInt(parts[0],-1);Material m=Material.matchMaterial(parts[1]);if(m==null)continue;
                ItemStack item=new ItemStack(m,Math.max(1,Math.min(m.getMaxStackSize(),parseInt(parts[2],1))));
                if(slot>=0&&slot<36)p.getInventory().setItem(slot,item);
                else if(slot>=45&&slot<=48)armor[slot-45]=item;
                else if(slot==49)p.getInventory().setItemInOffHand(item);
            }
            p.getInventory().setArmorContents(armor);
            return;
        }
        List<String> items=getConfig().getStringList("kits."+kit+".items");
        for(String raw:items){String[] parts=raw.split(":"); Material m=Material.matchMaterial(parts[0]); if(m==null)continue;
            int amount=parts.length>1?parseInt(parts[1],1):1; p.getInventory().addItem(new ItemStack(m,Math.max(1,Math.min(m.getMaxStackSize(),amount))));}
    }
    private int parseInt(String s,int fallback){try{return Integer.parseInt(s);}catch(Exception e){return fallback;}}
    private void endMatch(Player winner, boolean won, String reason) {
        UUID id=winner.getUniqueId(), otherId=opponents.get(id);
        if(otherId==null)return;
        Player loser=Bukkit.getPlayer(otherId);
        opponents.remove(id); opponents.remove(otherId);
        int elapsed=(int)((System.currentTimeMillis()-matchStart.getOrDefault(id,System.currentTimeMillis()))/1000);
        int max=getConfig().getInt("settings.max-rating-change",100), min=getConfig().getInt("settings.min-rating-change",50);
        int delta=Math.max(min,Math.min(max,min+(elapsed/30)*5));
        if(won){ratings.put(id,Math.max(0,rating(id)+delta)); if(loser!=null)ratings.put(otherId,Math.max(0,rating(otherId)-delta)); msg(winner,"Победа! Рейтинг +"+delta+"."); if(loser!=null)msg(loser,"Поражение. Рейтинг -"+delta+".");}
        if(loser!=null) restore(loser);
        restore(winner);
        msg(winner,reason); if(loser!=null)msg(loser,reason);
        String arena=playerArena.remove(id); playerArena.remove(otherId); if(arena!=null)busyArenas.remove(arena);
        matchStart.remove(id);matchStart.remove(otherId);matchKit.remove(id);matchKit.remove(otherId);saveData();
    }
    private void restore(Player p) {
        UUID id=p.getUniqueId(); p.getInventory().clear();
        if(savedItems.containsKey(id))p.getInventory().setContents(savedItems.remove(id));
        if(savedArmor.containsKey(id))p.getInventory().setArmorContents(savedArmor.remove(id));
        Location back=returns.remove(id); if(back!=null && back.getWorld()!=null)p.teleport(back);
        p.setFireTicks(0);p.setHealth(Math.min(p.getMaxHealth(),20));p.setFoodLevel(20);p.setSaturation(20);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity(); UUID opp=opponents.get(p.getUniqueId()); if(opp==null)return;
        e.getDrops().clear(); e.setDroppedExp(0);
        Player winner=Bukkit.getPlayer(opp);
        if(winner!=null) endMatch(winner,true,"Дуэль завершена.");
        else { opponents.remove(p.getUniqueId()); restore(p); }
    }
    @EventHandler public void onQuit(PlayerQuitEvent e) {
        Player p=e.getPlayer(); queue.remove(p.getUniqueId()); clearQueueState(p.getUniqueId());
        UUID opp=opponents.get(p.getUniqueId()); if(opp!=null){Player other=Bukkit.getPlayer(opp); if(other!=null)endMatch(other,true,"Соперник вышел — победа засчитана."); else opponents.remove(p.getUniqueId());}
        UUID inviter=invitations.remove(p.getUniqueId()); if(inviter!=null)invitations.remove(inviter);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if(command.getName().equalsIgnoreCase("dueladmin"))return admin(sender,args);
        if(!(sender instanceof Player p)){msg(sender,"Эту команду может использовать только игрок.");return true;}
        if(args.length==0){openKits(p);return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "cancel" -> {leaveQueue(p);msg(p,"Поиск отменён.");}
            case "stats" -> msg(p,"Рейтинг: "+rating(p.getUniqueId())+" | Изумруды: "+emeralds(p.getUniqueId()));
            case "accept" -> {UUID from=invitations.remove(p.getUniqueId());Player challenger=from==null?null:Bukkit.getPlayer(from);if(challenger==null){msg(p,"Приглашение не найдено.");return true;}invitations.remove(from);startMatch(challenger,p,getConfig().getString("settings.queue-kit","default"));}
            case "deny" -> {UUID from=invitations.remove(p.getUniqueId());Player challenger=from==null?null:Bukkit.getPlayer(from);if(challenger!=null)msg(challenger,p.getName()+" отклонил вызов.");msg(p,"Вызов отклонён.");}
            default -> {Player target=Bukkit.getPlayerExact(args[0]);if(target==null||target.equals(p)){msg(p,"Игрок не найден или это ты.");return true;}if(opponents.containsKey(target.getUniqueId())){msg(p,"Игрок уже в дуэли.");return true;}invitations.put(target.getUniqueId(),p.getUniqueId());msg(p,"Вызов отправлен игроку "+target.getName()+".");msg(target,p.getName()+" вызывает тебя на дуэль! Напиши /duel accept или /duel deny.");}
        }return true;
    }
    private boolean admin(CommandSender s,String[] a) {
        if(!s.hasPermission("woontime.duels.admin")){msg(s,"Недостаточно прав.");return true;}
        if(a.length>=3 && a[0].equalsIgnoreCase("rating")){Player p=Bukkit.getPlayerExact(a[1]);if(p==null){msg(s,"Игрок должен быть онлайн.");return true;}ratings.put(p.getUniqueId(),Math.max(0,parseInt(a[2],1000)));msg(s,"Рейтинг установлен: "+ratings.get(p.getUniqueId()));saveData();return true;}
        if(a.length>=3 && a[0].equalsIgnoreCase("emeralds")){Player p=Bukkit.getPlayerExact(a[1]);if(p==null){msg(s,"Игрок должен быть онлайн.");return true;}emeralds.put(p.getUniqueId(),Math.max(0,parseInt(a[2],0)));msg(s,"Баланс изумрудов установлен.");saveData();return true;}
        if(a.length>=4 && a[0].equalsIgnoreCase("arena") && a[1].equalsIgnoreCase("setspawn") && s instanceof Player p){String path="arenas."+a[2]+".spawn"+a[3];Location l=p.getLocation();getConfig().set(path+".world",l.getWorld().getName());getConfig().set(path+".x",l.getX());getConfig().set(path+".y",l.getY());getConfig().set(path+".z",l.getZ());getConfig().set(path+".yaw",l.getYaw());getConfig().set(path+".pitch",l.getPitch());saveConfig();msg(s,"Спавн "+a[3]+" арены "+a[2]+" сохранён.");return true;}
        if(a.length>=3 && a[0].equalsIgnoreCase("kit") && a[1].equalsIgnoreCase("create") && s instanceof Player p){String kit=a[2];List<String> items=new ArrayList<>();for(ItemStack it:p.getInventory().getContents())if(it!=null)items.add(it.getType().name()+":"+it.getAmount());getConfig().set("kits."+kit+".items",items);saveConfig();msg(s,"Набор "+kit+" сохранён из твоего инвентаря.");return true;}
        msg(s,"Использование: /dueladmin arena setspawn <арена> <1|2> | kit create <имя> | rating <ник> <число> | emeralds <ник> <число>");return true;
    }
    public int getRating(UUID id){return rating(id);}
    public int getEmeralds(UUID id){return emeralds(id);}
    private static final class netPlaceholderExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        private final WoonTimeDuels plugin;
        netPlaceholderExpansion(WoonTimeDuels plugin){this.plugin=plugin;}
        public String getIdentifier(){return "woontime";} public String getAuthor(){return "WoonTime";}
        public String getVersion(){return "1.0.0";} public boolean persist(){return true;}
        public String onRequest(org.bukkit.OfflinePlayer player,String params){
            if(player==null)return "";
            if(params.equalsIgnoreCase("rating"))return String.valueOf(plugin.getRating(player.getUniqueId()));
            if(params.equalsIgnoreCase("emeralds"))return String.valueOf(plugin.getEmeralds(player.getUniqueId()));
            return null;
        }
    }
}