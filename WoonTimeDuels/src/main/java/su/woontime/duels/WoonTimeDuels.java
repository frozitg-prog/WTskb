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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.Action;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import org.bukkit.attribute.Attribute;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
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
    private final Map<UUID,Integer> bestRatings = new HashMap<>();
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
    private final Map<UUID,List<String>> pendingKitLayouts = new HashMap<>();
    private final Set<UUID> returnToKitSelection = new HashSet<>();
    private static final String PREVIEW_TITLE = ChatColor.LIGHT_PURPLE + "Предпросмотр кита";
    private final Map<UUID,ItemStack[]> editorSavedItems = new HashMap<>();
    private final Map<UUID,ItemStack[]> editorSavedArmor = new HashMap<>();
    private final Map<UUID,ItemStack> editorSavedOffhand = new HashMap<>();
    private final Set<UUID> countdownFrozen = new HashSet<>();
    private final Map<String,List<UUID>> leaderboardEntities = new HashMap<>();
    private final Set<UUID> pendingReturn = new HashSet<>();
    private final YamlConfiguration personalKits = new YamlConfiguration();
    private File personalKitsFile;
    private File dataFile;
    private YamlConfiguration data;

    @Override public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("duel")).setExecutor(this);
        Objects.requireNonNull(getCommand("dueladmin")).setExecutor(this);
        Objects.requireNonNull(getCommand("menu")).setExecutor(this);
        Objects.requireNonNull(getCommand("stats")).setExecutor(this);
        personalKitsFile = new File(getDataFolder(), "personal-kits.yml");
        try { if (!personalKitsFile.exists()) { getDataFolder().mkdirs(); personalKitsFile.createNewFile(); } personalKits.load(personalKitsFile); } catch (Exception ex) { getLogger().warning("Could not load personal kits: " + ex.getMessage()); }
        dataFile = new File(getDataFolder(), "players.yml");
        data = YamlConfiguration.loadConfiguration(dataFile);
        migrateLegacyLeaderboards();
        refreshLeaderboards();
        new BukkitRunnable(){ public void run(){ refreshLeaderboards(); } }.runTaskTimer(this,1200L,1200L);
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
    private int stat(UUID id,String key){return data.getInt(id+".stats."+key,0);}
    private void addStat(UUID id,String key,int amount){data.set(id+".stats."+key,stat(id,key)+amount);}
    private int bestRating(UUID id){return bestRatings.computeIfAbsent(id,k->data.getInt(k+".best-rating",rating(k)));}
    private String serializeItem(ItemStack item){try{ByteArrayOutputStream b=new ByteArrayOutputStream();BukkitObjectOutputStream o=new BukkitObjectOutputStream(b);o.writeObject(item);o.close();return Base64.getEncoder().encodeToString(b.toByteArray());}catch(IOException ex){getLogger().warning("Не удалось сохранить предмет: "+ex.getMessage());return "";}}
    private ItemStack deserializeItem(String raw){try{BukkitObjectInputStream in=new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(raw)));Object o=in.readObject();in.close();return o instanceof ItemStack item?item:null;}catch(Exception ex){return null;}}
    private void saveData() {
        for (UUID id : ratings.keySet()) data.set(id + ".rating", ratings.get(id));
        for (UUID id : emeralds.keySet()) data.set(id + ".emeralds", emeralds.get(id));
        for (UUID id : bestRatings.keySet()) data.set(id + ".best-rating", bestRatings.get(id));
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
    private static final String EDITOR_TITLE = "Закройте инвентарь чтобы сохранить";
    private static final String MAIN_MENU_TITLE = ChatColor.LIGHT_PURPLE + "✦ WoonTime • Меню";
    private static final String STATS_MENU_TITLE = ChatColor.LIGHT_PURPLE + "✦ WoonTime • Статистика";
    private ItemStack namedItem(Material material,String name,List<String> lore){ItemStack i=new ItemStack(material);ItemMeta m=i.getItemMeta();m.setDisplayName(ChatColor.LIGHT_PURPLE+name);if(lore!=null)m.setLore(lore);i.setItemMeta(m);return i;}
    private void fillPinkBorder(Inventory inv){ItemStack pane=namedItem(Material.PINK_STAINED_GLASS_PANE,"woontime.su",List.of(ChatColor.GRAY+"WoonTime.su"));for(int i=0;i<inv.getSize();i++)if(i<9||i>=inv.getSize()-9||i%9==0||i%9==8)inv.setItem(i,pane);}
    private void openMainMenu(Player p){Inventory inv=Bukkit.createInventory(null,45,MAIN_MENU_TITLE);fillPinkBorder(inv);inv.setItem(20,namedItem(Material.IRON_SWORD,"Найти дуэль",List.of(ChatColor.GRAY+"Открыть список наборов")));inv.setItem(22,namedItem(Material.BOOK,"Статистика",List.of(ChatColor.GRAY+"Твои достижения")));inv.setItem(24,namedItem(Material.NETHERITE_SCRAP,"Скин на трапку",List.of(ChatColor.GRAY+"В разработке")));p.openInventory(inv);}
    private void openStats(Player p){UUID id=p.getUniqueId();Inventory inv=Bukkit.createInventory(null,45,STATS_MENU_TITLE);fillPinkBorder(inv);int k=p.getStatistic(Statistic.PLAYER_KILLS),d=p.getStatistic(Statistic.DEATHS);double kd=d==0?k:(double)k/d;long h=p.getStatistic(Statistic.PLAY_ONE_MINUTE)/20L/3600L;inv.setItem(10,namedItem(Material.IRON_SWORD,"Всего убийств: "+k,null));inv.setItem(12,namedItem(Material.TOTEM_OF_UNDYING,"Всего побед: "+stat(id,"wins"),null));inv.setItem(14,namedItem(Material.SKELETON_SKULL,"Всего смертей: "+d,null));inv.setItem(16,namedItem(Material.DIAMOND_SWORD,String.format(Locale.US,"КД: %.2f",kd),null));inv.setItem(20,namedItem(Material.CLOCK,"Наиграно: "+h+" ч.",null));inv.setItem(22,namedItem(Material.NETHERITE_SWORD,"Сыграно дуэлей: "+stat(id,"played"),null));inv.setItem(24,namedItem(Material.NETHERITE_INGOT,"Лучший рейтинг: "+bestRating(id),null));inv.setItem(31,namedItem(Material.PAPER,"Текущий рейтинг: "+rating(id),null));p.openInventory(inv);}
    @EventHandler public void onEditorDrag(InventoryDragEvent e){
        if(!e.getView().getTitle().equals(EDITOR_TITLE))return;
        for(int raw:e.getRawSlots())if(!isKitEditableSlot(raw)){e.setCancelled(true);return;}
    }
    private boolean isKitEditableSlot(int raw){return (raw>=0&&raw<=35)||(raw>=45&&raw<=48)||raw==50;}
    private ItemStack editorPane(){return namedItem(Material.BLUE_STAINED_GLASS_PANE,ChatColor.BLUE+"woontime.su",null);}
    private void fillEditorDecorations(Inventory inv){
        ItemStack pane=editorPane();for(int slot=36;slot<=44;slot++)inv.setItem(slot,pane.clone());inv.setItem(49,pane.clone());inv.setItem(52,pane.clone());inv.setItem(53,pane.clone());
        inv.setItem(51,namedItem(Material.ARROW,ChatColor.YELLOW+"Вернуться к выбору китов",List.of(ChatColor.GRAY+"Закрыть редактор и сохранить изменения")));
    }
    @EventHandler public void onSelectorSwordDrop(PlayerDropItemEvent e){
        ItemStack item=e.getItemDrop().getItemStack();
        if(item.getType().name().endsWith("_SWORD")&&item.hasItemMeta()&&item.getItemMeta().hasDisplayName()){
            String name=ChatColor.stripColor(item.getItemMeta().getDisplayName()).toLowerCase(Locale.ROOT);
            if(name.contains("duel")||name.contains("дуэл")||name.contains("woontime")||name.contains("поиск")){
                e.setCancelled(true);
                e.getItemDrop().remove();
                msg(e.getPlayer(),"Этот меч нельзя выбросить.");
            }
        }
    }
    @EventHandler public void onFoodLevelChange(FoodLevelChangeEvent e){if(e.getEntity() instanceof Player)e.setCancelled(true);}
    @EventHandler public void onCountdownMove(PlayerMoveEvent e){
        if(!countdownFrozen.contains(e.getPlayer().getUniqueId())||e.getTo()==null)return;
        if(e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getY()!=e.getTo().getY()||e.getFrom().getZ()!=e.getTo().getZ())
            e.setTo(new Location(e.getFrom().getWorld(),e.getFrom().getX(),e.getFrom().getY(),e.getFrom().getZ(),e.getTo().getYaw(),e.getTo().getPitch()));
    }
    @EventHandler public void onRespawn(PlayerRespawnEvent e){if(opponents.containsKey(e.getPlayer().getUniqueId()))return;World w=Bukkit.getWorld(getConfig().getString("settings.arena-world","world"));if(w!=null)e.setRespawnLocation(w.getSpawnLocation());}

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
        if(title.startsWith(PREVIEW_TITLE)){e.setCancelled(true);if(e.getRawSlot()==52){List<String> layout=pendingKitLayouts.remove(p.getUniqueId());String kit=editingKits.remove(p.getUniqueId());if(layout!=null&&kit!=null){personalKits.set(p.getUniqueId()+"."+kit+".layout",layout);try{personalKits.save(personalKitsFile);}catch(IOException ex){getLogger().warning("Could not save personal kit: "+ex.getMessage());}msg(p,"Изменения кита "+kit+" сохранены.");}boolean back=returnToKitSelection.remove(p.getUniqueId());p.closeInventory();if(back)Bukkit.getScheduler().runTask(this,()->openKits(p));return;}if(e.getRawSlot()==53){pendingKitLayouts.remove(p.getUniqueId());editingKits.remove(p.getUniqueId());msg(p,"Сохранение кита отменено.");boolean back=returnToKitSelection.remove(p.getUniqueId());p.closeInventory();if(back)Bukkit.getScheduler().runTask(this,()->openKits(p));return;}return;}
        if(title.equals(MAIN_MENU_TITLE)){e.setCancelled(true);ItemStack it=e.getCurrentItem();if(it==null||!it.hasItemMeta())return;String n=ChatColor.stripColor(it.getItemMeta().getDisplayName());if(n.equalsIgnoreCase("Найти дуэль"))openKits(p);else if(n.equalsIgnoreCase("Статистика"))openStats(p);else if(n.equalsIgnoreCase("Скин на трапку"))msg(p,"Эта функция пока в разработке.");return;}
        if(title.equals(STATS_MENU_TITLE)){e.setCancelled(true);return;}
        if(title.equals(EDITOR_TITLE)){int raw=e.getRawSlot();if(raw==51){e.setCancelled(true);returnToKitSelection.add(p.getUniqueId());p.closeInventory();return;}if(!isKitEditableSlot(raw)||e.getClickedInventory()!=e.getView().getTopInventory()||e.getClick().isShiftClick()||e.getClick().isKeyboardClick()||e.getClick()==ClickType.DOUBLE_CLICK||e.getClick()==ClickType.DROP||e.getClick()==ClickType.CONTROL_DROP){e.setCancelled(true);return;}if(raw>=45&&raw<=48){ItemStack cursor=e.getCursor();if(cursor!=null&&!cursor.getType().isAir()&&!isArmorForSlot(raw,cursor)){e.setCancelled(true);return;}}return;}
    }
    private void openKitEditor(Player p,String kit) {
        UUID id=p.getUniqueId();
        editorSavedItems.put(id,p.getInventory().getContents().clone());
        editorSavedArmor.put(id,p.getInventory().getArmorContents().clone());
        editorSavedOffhand.put(id,p.getInventory().getItemInOffHand().clone());
        editingKits.put(id,kit);
        Inventory edit=Bukkit.createInventory(null,54,EDITOR_TITLE);
        List<String> saved=personalKits.getStringList(id+"."+kit+".layout");
        if(!saved.isEmpty()) {
            for(String raw:saved){int sep=raw.indexOf('@');if(sep>0){int slot=parseInt(raw.substring(0,sep),-1);ItemStack it=deserializeItem(raw.substring(sep+1));if(slot>=0&&isKitEditableSlot(slot)&&it!=null)edit.setItem(slot,normalizeEditorSlot(slot,it));}else{String[] parts=raw.split(":",3);if(parts.length>=3){int slot=parseInt(parts[0],-1);Material mat=Material.matchMaterial(parts[1]);if(slot>=0&&isKitEditableSlot(slot)&&mat!=null)edit.setItem(slot,normalizeEditorSlot(slot,new ItemStack(mat,Math.max(1,parseInt(parts[2],1)))));}}}
        } else {
            for(ItemStack item:defaultKitItems(kit)){String n=item.getType().name();if(n.endsWith("_HELMET"))edit.setItem(45,item.clone());else if(n.endsWith("_CHESTPLATE"))edit.setItem(46,item.clone());else if(n.endsWith("_LEGGINGS"))edit.setItem(47,item.clone());else if(n.endsWith("_BOOTS"))edit.setItem(48,item.clone());else {int slot=firstFreeKitSlot(edit);if(slot>=0)edit.setItem(slot,item.clone());}}
        }
        fillEditorDecorations(edit);
        p.sendMessage(ChatColor.LIGHT_PURPLE+"Редактор кита: перемещай только предметы набора. Закрой инвентарь для сохранения.");
        p.openInventory(edit);
    }
    private int firstFreeKitSlot(Inventory inv){for(int slot=0;slot<36;slot++)if(inv.getItem(slot)==null||inv.getItem(slot).getType().isAir())return slot;return -1;}
    private boolean isArmorForSlot(int slot,ItemStack item){String n=item.getType().name();return switch(slot){case 45->n.endsWith("_HELMET")||item.getType()==Material.TURTLE_HELMET;case 46->n.endsWith("_CHESTPLATE");case 47->n.endsWith("_LEGGINGS");case 48->n.endsWith("_BOOTS");default->false;};}
    private ItemStack normalizeEditorSlot(int slot,ItemStack item){if(slot>=45&&slot<=48){String n=item.getType().name();int expected=slot-45;if((expected==0&&!n.endsWith("_HELMET"))||(expected==1&&!n.endsWith("_CHESTPLATE"))||(expected==2&&!n.endsWith("_LEGGINGS"))||(expected==3&&!n.endsWith("_BOOTS")))return null;}return item.clone();}
    private List<ItemStack> defaultKitItems(String kit) {
        List<ItemStack> result=new ArrayList<>();
        for(Object value:getConfig().getList("kits."+kit+".items",List.of())){if(value instanceof ItemStack item){result.add(item.clone());continue;}if(value instanceof String raw){String[] parts=raw.split(":");Material m=Material.matchMaterial(parts[0]);if(m!=null){int amount=parts.length>1?parseInt(parts[1],1):1;result.add(new ItemStack(m,Math.max(1,Math.min(m.getMaxStackSize(),amount))));}}}
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
        Inventory inv=e.getInventory();for(int slot=0;slot<=35;slot++){ItemStack it=inv.getItem(slot);if(it!=null&&!it.getType().isAir()){String encoded=serializeItem(it);if(!encoded.isEmpty())layout.add(slot+"@"+encoded);}}for(int slot=45;slot<=48;slot++){ItemStack it=inv.getItem(slot);if(it!=null&&!it.getType().isAir()){String encoded=serializeItem(it);if(!encoded.isEmpty())layout.add(slot+"@"+encoded);}}ItemStack kitOffhand=inv.getItem(50);if(kitOffhand!=null&&!kitOffhand.getType().isAir()){String encoded=serializeItem(kitOffhand);if(!encoded.isEmpty())layout.add("50@"+encoded);}
        pendingKitLayouts.put(id,layout);
        p.getInventory().clear();
        ItemStack[] contents=editorSavedItems.remove(id);if(contents!=null)p.getInventory().setContents(contents);
        ItemStack[] armor=editorSavedArmor.remove(id);if(armor!=null)p.getInventory().setArmorContents(armor);
        ItemStack offhand=editorSavedOffhand.remove(id);if(offhand!=null)p.getInventory().setItemInOffHand(offhand);
        Inventory preview=Bukkit.createInventory(null,54,PREVIEW_TITLE+" • "+kit);
        for(String raw:layout){int sep=raw.indexOf('@');if(sep>0){int slot=parseInt(raw.substring(0,sep),-1);ItemStack item=deserializeItem(raw.substring(sep+1));if(slot>=0&&isKitEditableSlot(slot)&&item!=null)preview.setItem(slot,item);}}
        preview.setItem(52,namedItem(Material.LIME_CONCRETE,"Сохранить изменения",List.of(ChatColor.GRAY+"Подтвердить и сохранить кит")));
        preview.setItem(53,namedItem(Material.RED_CONCRETE,"Отменить",List.of(ChatColor.GRAY+"Не сохранять изменения")));
        p.openInventory(preview);
    }
    private void joinQueue(Player p, String kit) {
        if (opponents.containsKey(p.getUniqueId())) { msg(p,"Ты уже в дуэли."); return; }
        if (queue.contains(p.getUniqueId())) { msg(p,"Ты уже ищешь соперника. /duel cancel — отменить."); return; }
        UUID match=null;
        for(UUID id: new ArrayList<>(queue)) {
            Player other=Bukkit.getPlayer(id);
            if(other!=null && !id.equals(p.getUniqueId()) && queueKits.getOrDefault(id, "").equalsIgnoreCase(kit)) { match=id; break; }
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
        countdownFrozen.add(x); countdownFrozen.add(y);
        new BukkitRunnable(){ int n=3;
            public void run(){ if(!opponents.containsKey(x)||!opponents.containsKey(y)||!a.isOnline()||!b.isOnline()){cancel();return;}
                if(n>0){a.sendTitle(ChatColor.LIGHT_PURPLE+""+n,b.getName(),0,20,0);b.sendTitle(ChatColor.LIGHT_PURPLE+""+n,a.getName(),0,20,0); a.playSound(a.getLocation(),Sound.BLOCK_NOTE_BLOCK_PLING,1,1);b.playSound(b.getLocation(),Sound.BLOCK_NOTE_BLOCK_PLING,1,1);n--;}
                else {a.sendTitle(ChatColor.GREEN+"БОЙ!", "",0,25,5);b.sendTitle(ChatColor.GREEN+"БОЙ!", "",0,25,5);a.setNoDamageTicks(0);b.setNoDamageTicks(0);countdownFrozen.remove(x);countdownFrozen.remove(y);cancel();}
            }}.runTaskTimer(this,0L,20L);
    }
    private void giveKit(Player p,String kit) {
        List<String> layout=personalKits.getStringList(p.getUniqueId()+"."+kit+".layout");
        if(!layout.isEmpty()) {
            ItemStack[] armor=new ItemStack[4];
            for(String raw:layout) {
                int sep=raw.indexOf('@');if(sep>0){int slot=parseInt(raw.substring(0,sep),-1);ItemStack item=deserializeItem(raw.substring(sep+1));if(item==null)continue;if(slot>=0&&slot<36)p.getInventory().setItem(slot,item);else if(slot>=45&&slot<=48)armor[slot-45]=item;else if(slot==49)p.getInventory().setItemInOffHand(item);continue;}
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
        for(ItemStack item:defaultKitItems(kit)){String n=item.getType().name();if(n.endsWith("_HELMET"))p.getInventory().setHelmet(item);else if(n.endsWith("_CHESTPLATE"))p.getInventory().setChestplate(item);else if(n.endsWith("_LEGGINGS"))p.getInventory().setLeggings(item);else if(n.endsWith("_BOOTS"))p.getInventory().setBoots(item);else p.getInventory().addItem(item);}
    }
    private int parseInt(String s,int fallback){try{return Integer.parseInt(s);}catch(Exception e){return fallback;}}
    private void endMatch(Player winner, boolean won, String reason) {
        UUID id=winner.getUniqueId(), otherId=opponents.get(id);
        if(otherId==null)return;
        Player loser=Bukkit.getPlayer(otherId);
        opponents.remove(id); opponents.remove(otherId);
        countdownFrozen.remove(id);countdownFrozen.remove(otherId);
        int elapsed=(int)((System.currentTimeMillis()-matchStart.getOrDefault(id,System.currentTimeMillis()))/1000);
        int delta=50;
        addStat(id,"played",1);addStat(otherId,"played",1);
        if(won){
            addStat(id,"wins",1);addStat(otherId,"losses",1);
            ratings.put(id,Math.max(0,rating(id)+delta));
            bestRatings.put(id,Math.max(bestRating(id),rating(id)));
            emeralds.put(id,emeralds(id)+1);
            if(loser!=null)ratings.put(otherId,Math.max(0,rating(otherId)-delta));
            String loserName=loser!=null?loser.getName():Bukkit.getOfflinePlayer(otherId).getName();
            if(loserName==null)loserName=otherId.toString().substring(0,8);
            String pinkLine=ChatColor.LIGHT_PURPLE+"=====================================";
            String[] resultLines = {
                pinkLine,
                ChatColor.WHITE+"Победу одержал "+ChatColor.LIGHT_PURPLE+winner.getName(),
                ChatColor.WHITE+"Игроку (Win) "+ChatColor.LIGHT_PURPLE+winner.getName()+ChatColor.WHITE+" начислено "+ChatColor.GREEN+"+50"+ChatColor.WHITE+" очков рейтинга",
                ChatColor.WHITE+"Игроку (Loss) "+ChatColor.LIGHT_PURPLE+loserName+ChatColor.WHITE+" забрано "+ChatColor.RED+"-50"+ChatColor.WHITE+" очков рейтинга",
                pinkLine
            };
            winner.sendMessage(resultLines);
            if(loser!=null) loser.sendMessage(resultLines);
            msg(winner,"Победа! +50 рейтинга и +1 алмаз.");
            if(loser!=null)msg(loser,"Поражение. -50 рейтинга.");
            saveData();
        }
        if(loser!=null && won){
            loser.setGameMode(GameMode.SPECTATOR);
            msg(loser,"Ты проиграл. Через 5 секунд вернёшься на спавн.");
            net.md_5.bungee.api.chat.TextComponent exit = new net.md_5.bungee.api.chat.TextComponent("[Выйти с дуэли]");
            exit.setColor(net.md_5.bungee.api.ChatColor.GREEN); exit.setBold(true);
            exit.setClickEvent(new net.md_5.bungee.api.chat.ClickEvent(net.md_5.bungee.api.chat.ClickEvent.Action.RUN_COMMAND,"/duel exit"));
            exit.setHoverEvent(new net.md_5.bungee.api.chat.HoverEvent(net.md_5.bungee.api.chat.HoverEvent.Action.SHOW_TEXT,new net.md_5.bungee.api.chat.hover.content.Text("Вернуться на спавн сейчас")));
            loser.spigot().sendMessage(exit);
            UUID loserId=otherId;pendingReturn.add(loserId);
            Bukkit.getScheduler().runTaskLater(this,()->{boolean shouldReturn=pendingReturn.remove(loserId);Player online=Bukkit.getPlayer(loserId);if(shouldReturn&&online!=null){restoreAtSpawn(online);online.setGameMode(GameMode.SURVIVAL);}},100L);
        } else if(loser!=null) restoreAtSpawn(loser);
        restoreAtSpawn(winner);
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
    private void restoreAtSpawn(Player p){
        UUID id=p.getUniqueId();p.getInventory().clear();
        if(savedItems.containsKey(id))p.getInventory().setContents(savedItems.remove(id));
        if(savedArmor.containsKey(id))p.getInventory().setArmorContents(savedArmor.remove(id));
        returns.remove(id);
        World w=Bukkit.getWorld(getConfig().getString("settings.arena-world","world"));
        if(w!=null)p.teleport(w.getSpawnLocation());
        p.setFireTicks(0);p.setHealth(Math.min(p.getMaxHealth(),20));p.setFoodLevel(20);p.setSaturation(20);
    }
    @EventHandler public void onDeath(PlayerDeathEvent e) {
        Player p=e.getEntity(); UUID opp=opponents.get(p.getUniqueId()); if(opp==null){Bukkit.getScheduler().runTask(this,()->{if(p.isOnline()&&p.isDead())p.spigot().respawn();});return;}
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
        if(command.getName().equalsIgnoreCase("menu")){if(sender instanceof Player player)openMainMenu(player);else msg(sender,"Команда только для игрока.");return true;}
        if(command.getName().equalsIgnoreCase("stats")){if(sender instanceof Player player)openStats(player);else msg(sender,"Команда только для игрока.");return true;}
        if(!(sender instanceof Player p)){msg(sender,"Эту команду может использовать только игрок.");return true;}
        if(args.length==0){openKits(p);return true;}
        switch(args[0].toLowerCase(Locale.ROOT)){
            case "cancel" -> {leaveQueue(p);msg(p,"Поиск отменён.");}
            case "exit" -> {if(pendingReturn.remove(p.getUniqueId())){restoreAtSpawn(p);p.setGameMode(GameMode.SURVIVAL);}else msg(p,"Ты сейчас не находишься в режиме ожидания выхода.");}
            case "stats" -> msg(p,"Рейтинг: "+rating(p.getUniqueId())+" | Изумруды: "+emeralds(p.getUniqueId()));
            case "accept" -> {UUID from=invitations.remove(p.getUniqueId());Player challenger=from==null?null:Bukkit.getPlayer(from);if(challenger==null){msg(p,"Приглашение не найдено.");return true;}invitations.remove(from);startMatch(challenger,p,getConfig().getString("settings.queue-kit","default"));}
            case "deny" -> {UUID from=invitations.remove(p.getUniqueId());Player challenger=from==null?null:Bukkit.getPlayer(from);if(challenger!=null)msg(challenger,p.getName()+" отклонил вызов.");msg(p,"Вызов отклонён.");}
            default -> {Player target=Bukkit.getPlayerExact(args[0]);if(target==null||target.equals(p)){msg(p,"Игрок не найден или это ты.");return true;}if(opponents.containsKey(target.getUniqueId())){msg(p,"Игрок уже в дуэли.");return true;}invitations.put(target.getUniqueId(),p.getUniqueId());msg(p,"Вызов отправлен игроку "+target.getName()+".");msg(target,p.getName()+" вызывает тебя на дуэль! Напиши /duel accept или /duel deny.");}
        }return true;
    }
    private boolean admin(CommandSender s,String[] a) {
        if(!s.hasPermission("woontime.duels.admin")){msg(s,"Недостаточно прав.");return true;}
        if(a.length>=2 && a[0].equalsIgnoreCase("hologram")){
            if(a[1].equalsIgnoreCase("removeall")){
                ConfigurationSection holograms=getConfig().getConfigurationSection("leaderboards.instances");
                Set<String> ids=holograms==null?new HashSet<>():new HashSet<>(holograms.getKeys(false));
                for(String id:ids)removeHologramEntities(id);
                // Remove legacy, untagged leaderboard stands around each configured location.
                // Older versions may have no scoreboard tags and names may differ, so identify
                // the plugin's invisible marker stands by their entity properties instead.
                int legacyRemoved=0;
                for(String id:ids){
                    Location base=loc("leaderboards.instances."+id);
                    if(base==null)continue;
                    for(Entity entity:new ArrayList<>(base.getWorld().getNearbyEntities(base,4.0,15.0,4.0))){
                        if(!(entity instanceof ArmorStand stand))continue;
                        if(!stand.isInvisible()||!stand.isMarker()||!stand.isCustomNameVisible())continue;
                        stand.remove();
                        legacyRemoved++;
                    }
                }
                getConfig().set("leaderboards.instances",null);
                getConfig().set("leaderboards.kills",null);
                getConfig().set("leaderboards.wins",null);
                saveConfig();
                msg(s,"Все голограммы лидерборда удалены. Старых голограмм удалено: "+legacyRemoved+".");return true;
            }
            if(a[1].equalsIgnoreCase("remove")){
                if(a.length<3){msg(s,"Использование: /dueladmin hologram remove <ID>");return true;}
                String id=a[2];
                ConfigurationSection holograms=getConfig().getConfigurationSection("leaderboards.instances");
                if(holograms==null||!holograms.contains(id)){msg(s,"Голограмма с ID "+id+" не найдена.");return true;}
                removeHologramEntities(id);getConfig().set("leaderboards.instances."+id,null);saveConfig();
                msg(s,"Голограмма "+id+" удалена.");return true;
            }
            String type=a[1].toLowerCase(Locale.ROOT);
            if(!type.equals("kills")&&!type.equals("wins")&&!type.equals("diamonds")){msg(s,"Использование: /dueladmin hologram <kills|wins|diamonds> | hologram remove <ID> | hologram removeall");return true;}
            if(!(s instanceof Player p)){msg(s,"Создавать голограммы можно только в игре.");return true;}
            ConfigurationSection holograms=getConfig().getConfigurationSection("leaderboards.instances");
            Set<String> ids=holograms==null?new HashSet<>():new HashSet<>(holograms.getKeys(false));
            int number=1;while(ids.contains("holo"+number))number++;
            String id="holo"+number;Location l=p.getLocation();String path="leaderboards.instances."+id;
            getConfig().set(path+".type",type);getConfig().set(path+".world",l.getWorld().getName());
            getConfig().set(path+".x",l.getX());getConfig().set(path+".y",l.getY());getConfig().set(path+".z",l.getZ());
            saveConfig();refreshLeaderboard(id,type,type.equals("kills")?"УБИЙСТВ ИГРОКОВ":type.equals("wins")?"ПОБЕД В ДУЭЛЯХ":"БАЛАНС АЛМАЗОВ",type.equals("kills"));
            msg(s,"Голограмма создана. ID: "+id+" — удалить: /dueladmin hologram remove "+id);return true;
        }
        if(a.length>=3 && a[0].equalsIgnoreCase("rating")){Player p=Bukkit.getPlayerExact(a[1]);if(p==null){msg(s,"Игрок должен быть онлайн.");return true;}ratings.put(p.getUniqueId(),Math.max(0,parseInt(a[2],1000)));msg(s,"Рейтинг установлен: "+ratings.get(p.getUniqueId()));saveData();return true;}
        if(a.length>=3 && a[0].equalsIgnoreCase("emeralds")){Player p=Bukkit.getPlayerExact(a[1]);if(p==null){msg(s,"Игрок должен быть онлайн.");return true;}emeralds.put(p.getUniqueId(),Math.max(0,parseInt(a[2],0)));msg(s,"Баланс изумрудов установлен.");saveData();return true;}
        if(a.length>=4 && a[0].equalsIgnoreCase("arena") && a[1].equalsIgnoreCase("setspawn") && s instanceof Player p){String path="arenas."+a[2]+".spawn"+a[3];Location l=p.getLocation();getConfig().set(path+".world",l.getWorld().getName());getConfig().set(path+".x",l.getX());getConfig().set(path+".y",l.getY());getConfig().set(path+".z",l.getZ());getConfig().set(path+".yaw",l.getYaw());getConfig().set(path+".pitch",l.getPitch());saveConfig();msg(s,"Спавн "+a[3]+" арены "+a[2]+" сохранён.");return true;}
        if(a.length>=3 && a[0].equalsIgnoreCase("kit") && a[1].equalsIgnoreCase("create") && s instanceof Player p){String kit=a[2];List<ItemStack> items=new ArrayList<>();for(ItemStack it:p.getInventory().getContents())if(it!=null&&!it.getType().isAir())items.add(it.clone());for(ItemStack it:p.getInventory().getArmorContents())if(it!=null&&!it.getType().isAir())items.add(it.clone());getConfig().set("kits."+kit+".items",items);saveConfig();msg(s,"Набор "+kit+" сохранён из твоего инвентаря.");refreshMenus();return true;}
        if(a.length>=3 && a[0].equalsIgnoreCase("kit") && a[1].equalsIgnoreCase("delete")){
            ConfigurationSection section=getConfig().getConfigurationSection("kits");
            String requested=a[2];
            String actual=null;
            if(section!=null)for(String key:section.getKeys(false))if(key.equalsIgnoreCase(requested)){actual=key;break;}
            if(actual==null){msg(s,"Кит '"+requested+"' не найден. Проверь название в config.yml.");return true;}
            if(section.getKeys(false).size()<=1){msg(s,"Нельзя удалить последний кит. Сначала создай другой: /dueladmin kit create <имя>");return true;}
            getConfig().set("kits."+actual,null);saveConfig();
            for(String playerKey:new HashSet<>(personalKits.getKeys(false)))personalKits.set(playerKey+"."+actual,null);
            try{personalKits.save(personalKitsFile);}catch(IOException ex){getLogger().warning("Could not save personal kits: "+ex.getMessage());}
            refreshMenus();msg(s,"Кит '"+actual+"' удалён.");return true;
        }
        msg(s,"Использование: /dueladmin hologram <kills|wins> | hologram removeall | arena setspawn <арена> <1|2> | kit create <имя> | kit delete <имя> | rating <ник> <число> | emeralds <ник> <число>");return true;
    }
    private void migrateLegacyLeaderboards(){
        for(String type:List.of("kills","wins")){
            String oldPath="leaderboards."+type;
            if(getConfig().contains(oldPath+".world")){
                ConfigurationSection section=getConfig().getConfigurationSection("leaderboards.instances");
                Set<String> ids=section==null?new HashSet<>():new HashSet<>(section.getKeys(false));
                int number=1;while(ids.contains("holo"+number))number++;
                String path="leaderboards.instances.holo"+number;
                for(String key:List.of("world","x","y","z"))if(getConfig().contains(oldPath+"."+key))getConfig().set(path+"."+key,getConfig().get(oldPath+"."+key));
                getConfig().set(path+".type",type);getConfig().set(oldPath,null);
            }
        }
        saveConfig();
    }
    private void refreshLeaderboards(){
        ConfigurationSection section=getConfig().getConfigurationSection("leaderboards.instances");
        if(section==null)return;
        for(String id:section.getKeys(false)){
            String type=section.getString(id+".type","kills");if(!type.equals("kills")&&!type.equals("wins")&&!type.equals("diamonds"))continue;
            refreshLeaderboard(id,type,type.equals("kills")?"УБИЙСТВ ИГРОКОВ":type.equals("wins")?"ПОБЕД В ДУЭЛЯХ":"БАЛАНС АЛМАЗОВ",type.equals("kills"));
        }
    }
    private void removeHologramEntities(String id){
        for(World world:Bukkit.getWorlds())for(Entity entity:world.getEntities())if(entity.getScoreboardTags().contains("woontime_hologram_"+id))entity.remove();
        for(UUID entityId:leaderboardEntities.getOrDefault(id,List.of())){Entity entity=Bukkit.getEntity(entityId);if(entity!=null)entity.remove();}
        leaderboardEntities.remove(id);
    }
    private void refreshLeaderboard(String id,String type,String title,boolean kills){
        removeHologramEntities(id);
        Location base=loc("leaderboards.instances."+id);if(base==null)return;
        List<UUID> known=new ArrayList<>();for(OfflinePlayer offline:Bukkit.getOfflinePlayers())if(offline.hasPlayedBefore()||offline.isOnline())known.add(offline.getUniqueId());
        for(String key:data.getKeys(false)){try{UUID uuid=UUID.fromString(key);if(!known.contains(uuid))known.add(uuid);}catch(IllegalArgumentException ignored){}}
        for(Player online:Bukkit.getOnlinePlayers())if(!known.contains(online.getUniqueId()))known.add(online.getUniqueId());
        known.sort((u,v)->Integer.compare(leaderboardScore(v,type,kills),leaderboardScore(u,type,kills)));
        List<UUID> spawned=new ArrayList<>();
        ArmorStand head=base.getWorld().spawn(base.clone().add(0,0.25,0),ArmorStand.class,as->{as.setInvisible(true);as.setMarker(true);as.setGravity(false);as.setCustomNameVisible(true);as.setCustomName(ChatColor.LIGHT_PURPLE+"✦ ТОП-10 "+title+" ✦");as.setPersistent(true);as.addScoreboardTag("woontime_hologram_"+id);});spawned.add(head.getUniqueId());
        int rank=0;for(UUID uuid:known){if(rank>=10)break;OfflinePlayer op=Bukkit.getOfflinePlayer(uuid);String name=op.getName();if(name==null)name=uuid.toString().substring(0,8);int score=leaderboardScore(uuid,type,kills);Location line=base.clone().add(0,-0.28*(rank+1),0);String text=ChatColor.WHITE+"#"+(rank+1)+" "+ChatColor.LIGHT_PURPLE+name+ChatColor.GRAY+" — "+ChatColor.WHITE+score;ArmorStand stand=base.getWorld().spawn(line,ArmorStand.class,as->{as.setInvisible(true);as.setMarker(true);as.setGravity(false);as.setCustomNameVisible(true);as.setCustomName(text);as.setPersistent(true);as.addScoreboardTag("woontime_hologram_"+id);});spawned.add(stand.getUniqueId());rank++;}
        leaderboardEntities.put(id,spawned);
    }
    private int leaderboardScore(UUID id,String type,boolean kills){return type.equals("diamonds")?getEmeralds(id):kills?getPlayerKills(id):getWins(id);}
    public int getRating(UUID id){return rating(id);}
    public int getEmeralds(UUID id){return emeralds(id);}
    public int getWins(UUID id){return stat(id,"wins");}
    public int getLosses(UUID id){return stat(id,"losses");}
    public int getPlayedDuels(UUID id){return stat(id,"played");}
    public int getPlayerKills(UUID id){return Bukkit.getOfflinePlayer(id).getStatistic(Statistic.PLAYER_KILLS);}
    public int getSearchingCount(){return queue.size();}
    public int getFightingCount(){return new HashSet<>(opponents.keySet()).size()/2;}
    private static final class netPlaceholderExpansion extends me.clip.placeholderapi.expansion.PlaceholderExpansion {
        private final WoonTimeDuels plugin;
        netPlaceholderExpansion(WoonTimeDuels plugin){this.plugin=plugin;}
        public String getIdentifier(){return "woontime";} public String getAuthor(){return "WoonTime";}
        public String getVersion(){return "1.0.0";} public boolean persist(){return true;}
        public String onRequest(org.bukkit.OfflinePlayer player,String params){
            if(player==null)return "";
            if(params.equalsIgnoreCase("rating"))return String.valueOf(plugin.getRating(player.getUniqueId()));
            if(params.equalsIgnoreCase("emeralds"))return String.valueOf(plugin.getEmeralds(player.getUniqueId()));
            if(params.equalsIgnoreCase("wins"))return String.valueOf(plugin.getWins(player.getUniqueId()));
            if(params.equalsIgnoreCase("losses"))return String.valueOf(plugin.getLosses(player.getUniqueId()));
            if(params.equalsIgnoreCase("played"))return String.valueOf(plugin.getPlayedDuels(player.getUniqueId()));
            if(params.equalsIgnoreCase("kills"))return String.valueOf(plugin.getPlayerKills(player.getUniqueId()));
            if(params.equalsIgnoreCase("searching"))return String.valueOf(plugin.getSearchingCount());
            if(params.equalsIgnoreCase("fighting"))return String.valueOf(plugin.getFightingCount());
            return null;
        }
    }
}