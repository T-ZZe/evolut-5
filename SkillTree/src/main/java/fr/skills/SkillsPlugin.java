package fr.skills;

import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * SkillTree 2.0 - arbre de compétences à 5 voies :
 * Vitesse, Force, Résistance, Fermier, Camouflage.
 * Chaque voie a 3 paliers ; le dernier débloque une capacité active
 * utilisable par clic droit avec le Sceptre de compétences.
 */
public class SkillsPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    // ---------- Gain de points ----------
    static final int POINTS_PLAYER_KILL = 4;
    static final int MOBS_PER_REWARD = 150;
    static final int POINTS_MOBS = 2;
    static final int CROPS_PER_REWARD = 500;
    static final int POINTS_CROPS = 3;
    static final int ORES_PER_REWARD = 25;
    static final int POINTS_ORES = 4;

    static final int WITHER_CHANCE_PERCENT = 10;
    static final double BONUS_HEALTH = 6.0;
    static final long DASH_DELAY_MS = 600L;

    static final UUID PACK_ID = UUID.nameUUIDFromBytes("skilltree-resource-pack".getBytes());
    static final String CMD_SCEPTRE = "skilltree_sceptre";

    static final Set<Material> ORES = EnumSet.of(Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE, Material.ANCIENT_DEBRIS);
    static final Set<Material> CROPS = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS);
    /** Cultures que la capacité du Fermier fait pousser. */
    static final Set<Material> GROWABLE = EnumSet.of(Material.WHEAT, Material.CARROTS, Material.POTATOES,
            Material.BEETROOTS, Material.NETHER_WART);

    static final List<Node> NODES = new ArrayList<>();
    static final Set<PotionEffectType> MANAGED = new HashSet<>();
    static Node N_CAMO1;
    static Node N_CAMO2;
    static Node N_WITHER;

    // ---------- État ----------
    private final Map<UUID, Data> data = new HashMap<>();
    private final Map<UUID, Deque<Long>> dashUses = new HashMap<>();
    private final Map<UUID, Long> pendingReset = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<String> placed = new HashSet<>();

    // Camouflage
    private final Map<UUID, Long> veilUntil = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private final Map<UUID, Integer> stillTicks = new HashMap<>();
    private final Map<UUID, Location> lastPos = new HashMap<>();

    // Resource pack
    private PackHost pack;
    private final Set<UUID> packLoaded = new HashSet<>();

    private File dataFile;
    private File placedFile;
    private NamespacedKey wandKey;
    private NamespacedKey serialKey;
    private NamespacedKey healthKey;

    // =====================================================================
    //  Définition de l'arbre
    // =====================================================================
    private static Node add(Node n) {
        NODES.add(n);
        MANAGED.addAll(n.effects.keySet());
        return n;
    }

    static Node nodeBySlot(int slot) {
        for (Node n : NODES) if (n.slot == slot) return n;
        return null;
    }

    static Node nodeOf(Path p, int tier) {
        for (Node n : NODES) if (n.path == p && n.tier == tier) return n;
        return null;
    }

    /** Capacité active de la voie choisie (dernier palier). */
    static Node ultimate(Data d) {
        Path p = Path.byId(d.path);
        return p == null ? null : nodeOf(p, 3);
    }

    static {
        // ----- Vitesse -----
        add(new Node("speed1", Path.SPEED, 1, 0, Material.FEATHER, "Vitesse I",
                "Effet Vitesse I permanent.").eff(PotionEffectType.SPEED, 0));
        add(new Node("speed2", Path.SPEED, 2, 15, Material.SUGAR, "Vitesse II",
                "Effet Vitesse II permanent.").eff(PotionEffectType.SPEED, 1));
        add(new Node("dash", Path.SPEED, 3, 30, Material.ENDER_PEARL, "Dash",
                "Capacité : clic droit avec le sceptre pour foncer vers l'avant (3 utilisations / 30 s).")
                .ability("dash"));

        // ----- Force -----
        add(new Node("force1", Path.FORCE, 1, 5, Material.IRON_SWORD, "Force I",
                "Effet Force I permanent.").eff(PotionEffectType.STRENGTH, 0));
        add(new Node("force2", Path.FORCE, 2, 20, Material.DIAMOND_SWORD, "Force II",
                "Effet Force II permanent.").eff(PotionEffectType.STRENGTH, 1));
        N_WITHER = add(new Node("wither", Path.FORCE, 3, 30, Material.WITHER_SKELETON_SKULL, "Fureur du Wither",
                "Passif : 10% de chance d'infliger Wither II (5 s) en frappant. "
                        + "Capacité : clic droit avec le sceptre = onde de choc qui blesse et repousse les ennemis, "
                        + "puis Force III temporaire.").wither().ability("shock"));

        // ----- Résistance -----
        add(new Node("res1", Path.RESIST, 1, 0, Material.IRON_CHESTPLATE, "Résistance I",
                "Effet Résistance I permanent.").eff(PotionEffectType.RESISTANCE, 0));
        add(new Node("res2", Path.RESIST, 2, 18, Material.DIAMOND_CHESTPLATE, "Résistance II + Feu",
                "Résistance II et Résistance au feu I permanents.")
                .eff(PotionEffectType.RESISTANCE, 1).eff(PotionEffectType.FIRE_RESISTANCE, 0));
        add(new Node("res3", Path.RESIST, 3, 30, Material.GOLDEN_APPLE, "Bastion",
                "Passif : 3 cœurs de vie maximum en plus. "
                        + "Capacité : clic droit avec le sceptre = Résistance III temporaire.")
                .health().ability("bastion"));

        // ----- Fermier -----
        add(new Node("farm1", Path.FARM, 1, 0, Material.STONE_HOE, "Célérité I",
                "Effet Célérité I permanent.").eff(PotionEffectType.HASTE, 0));
        add(new Node("farm2", Path.FARM, 2, 14, Material.EMERALD, "Célérité II + Héros III",
                "Célérité II et Héros du village III permanents.")
                .eff(PotionEffectType.HASTE, 1).eff(PotionEffectType.HERO_OF_THE_VILLAGE, 2));
        add(new Node("farm3", Path.FARM, 3, 28, Material.HEART_OF_THE_SEA, "Bénédiction de la Terre",
                "Passif : Force du conduit et Héros du village V. "
                        + "Capacité : clic droit avec le sceptre = les cultures autour de toi arrivent à maturité.")
                .eff(PotionEffectType.CONDUIT_POWER, 0).eff(PotionEffectType.HERO_OF_THE_VILLAGE, 4)
                .ability("grow"));

        // ----- Camouflage -----
        N_CAMO1 = add(new Node("camo1", Path.CAMO, 1, 0, Material.SPIDER_EYE, "Œil de l'ombre",
                "Vision nocturne permanente. Accroupi, les monstres ne te repèrent que de très près.")
                .eff(PotionEffectType.NIGHT_VISION, 0));
        N_CAMO2 = add(new Node("camo2", Path.CAMO, 2, 16, Material.FERMENTED_SPIDER_EYE, "Fondu dans le décor",
                "Reste accroupi et immobile quelques secondes : tu deviens invisible "
                        + "(armure cachée, monstres aveugles). Bouger ou frapper rompt le camouflage."));
        add(new Node("camo3", Path.CAMO, 3, 30, Material.PHANTOM_MEMBRANE, "Voile d'ombre",
                "Capacité : clic droit avec le sceptre = invisibilité totale 12 s, "
                        + "les monstres t'oublient. Frapper rompt le voile.").ability("veil"));
    }

    // =====================================================================
    //  Cycle de vie
    // =====================================================================
    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "wand");
        serialKey = new NamespacedKey(this, "wand_serial");
        healthKey = new NamespacedKey(this, "bonus_health");
        dataFile = new File(getDataFolder(), "data.yml");
        placedFile = new File(getDataFolder(), "placed.yml");
        load();

        pack = new PackHost(this);
        if (!pack.start()) pack.url = null;

        getServer().getPluginManager().registerEvents(this, this);
        var cmd = getCommand("skills");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
        getServer().getScheduler().runTaskTimer(this, this::save, 6000L, 6000L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player p : getServer().getOnlinePlayers()) applyEffects(p);
        }, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, this::tickCamo, 10L, 10L);
    }

    @Override
    public void onDisable() {
        save();
        if (pack != null) pack.stop();
    }

    private int cfgInt(String path, int def) {
        return getConfig().getInt(path, def);
    }

    private double cfgDouble(String path, double def) {
        return getConfig().getDouble(path, def);
    }

    // =====================================================================
    //  Sauvegarde
    // =====================================================================
    private void load() {
        if (placedFile.exists()) {
            placed.addAll(YamlConfiguration.loadConfiguration(placedFile).getStringList("blocks"));
        }
        if (!dataFile.exists()) return;
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(dataFile);
        for (String key : cfg.getKeys(false)) {
            try {
                UUID id = UUID.fromString(key);
                Data d = new Data();
                d.points = cfg.getInt(key + ".points");
                d.kills = cfg.getInt(key + ".kills");
                d.playerKills = cfg.getInt(key + ".playerKills");
                d.crops = cfg.getInt(key + ".crops");
                d.ores = cfg.getInt(key + ".ores");
                d.path = cfg.getString(key + ".path");
                d.wand = cfg.getString(key + ".wand");
                d.owned.addAll(cfg.getStringList(key + ".owned"));
                d.off.addAll(cfg.getStringList(key + ".off"));
                ConfigurationSection old = cfg.getConfigurationSection(key + ".levels");
                if (old != null) {
                    for (String k : old.getKeys(false)) {
                        int l = old.getInt(k);
                        d.points += l * (l + 1) / 2;
                    }
                }
                data.put(id, d);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    private void save() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Map.Entry<UUID, Data> en : data.entrySet()) {
            String k = en.getKey().toString();
            Data d = en.getValue();
            cfg.set(k + ".points", d.points);
            cfg.set(k + ".kills", d.kills);
            cfg.set(k + ".playerKills", d.playerKills);
            cfg.set(k + ".crops", d.crops);
            cfg.set(k + ".ores", d.ores);
            cfg.set(k + ".path", d.path);
            cfg.set(k + ".wand", d.wand);
            cfg.set(k + ".owned", new ArrayList<>(d.owned));
            cfg.set(k + ".off", new ArrayList<>(d.off));
        }
        try {
            getDataFolder().mkdirs();
            cfg.save(dataFile);
            YamlConfiguration pc = new YamlConfiguration();
            pc.set("blocks", new ArrayList<>(placed));
            pc.save(placedFile);
        } catch (IOException e) {
            getLogger().warning("Impossible de sauvegarder les données : " + e.getMessage());
        }
    }

    private Data data(Player p) {
        return data.computeIfAbsent(p.getUniqueId(), k -> new Data());
    }

    private boolean active(Data d, Node n) {
        return n != null && d.owned.contains(n.id) && !d.off.contains(n.id);
    }

    // =====================================================================
    //  Items / texte
    // =====================================================================
    private static Component c(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, String name, List<String> lore) {
        return item(m, name, lore, null, null);
    }

    /** Si le joueur a chargé le resource pack, utilise la texture custom "skilltree:<model>". */
    private ItemStack item(Material m, String name, List<String> lore, String model, Player viewer) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(c(name));
        if (lore != null) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(c(s));
            meta.lore(l);
        }
        if (model != null && viewer != null && packLoaded.contains(viewer.getUniqueId())) {
            NamespacedKey key = NamespacedKey.fromString("skilltree:" + model);
            if (key != null) meta.setItemModel(key);
        }
        it.setItemMeta(meta);
        return it;
    }

    private ItemStack createWand(String serial) {
        ItemStack it = item(Material.BLAZE_ROD, "§6Sceptre de compétences",
                List.of("§7Clic droit : capacité de ta voie",
                        "§8Lié à ton compte - non craftable."));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(serialKey, PersistentDataType.STRING, serial);
        // Texture custom si le pack est chargé (sinon : blaze rod classique)
        CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
        cmd.setStrings(List.of(CMD_SCEPTRE));
        meta.setCustomModelDataComponent(cmd);
        it.setItemMeta(meta);
        return it;
    }

    private boolean isWand(ItemStack it) {
        if (it == null || !it.hasItemMeta()) return false;
        return it.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    private boolean isValidWand(Player p, ItemStack it) {
        if (!isWand(it)) return false;
        String s = it.getItemMeta().getPersistentDataContainer().get(serialKey, PersistentDataType.STRING);
        return s != null && s.equals(data(p).wand);
    }

    private boolean hasValidWand(Player p) {
        for (ItemStack it : p.getInventory().getContents()) {
            if (isValidWand(p, it)) return true;
        }
        return isValidWand(p, p.getItemOnCursor());
    }

    private void giveWand(Player p) {
        Data d = data(p);
        Node ult = ultimate(d);
        if (ult == null || !d.owned.contains(ult.id)) {
            p.sendMessage(c("§cLe sceptre se débloque avec la capacité finale de ta voie (dernier palier)."));
            return;
        }
        if (hasValidWand(p)) {
            p.sendMessage(c("§eTu as déjà ton sceptre."));
            return;
        }
        String serial = UUID.randomUUID().toString();
        if (!p.getInventory().addItem(createWand(serial)).isEmpty()) {
            p.sendMessage(c("§cTon inventaire est plein."));
            return;
        }
        d.wand = serial;
        p.sendMessage(c("§6Tu as reçu ton Sceptre de compétences."));
    }

    private void removeWands(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isWand(contents[i])) p.getInventory().setItem(i, null);
        }
    }

    // =====================================================================
    //  Effets permanents
    // =====================================================================
    private void applyEffects(Player p) {
        Data d = data(p);
        Map<PotionEffectType, Integer> want = new HashMap<>();
        boolean wantHealth = false;
        for (Node n : NODES) {
            if (!active(d, n)) continue;
            for (Map.Entry<PotionEffectType, Integer> e : n.effects.entrySet()) {
                want.merge(e.getKey(), e.getValue(), Math::max);
            }
            if (n.health) wantHealth = true;
        }
        for (PotionEffectType t : MANAGED) {
            Integer amp = want.get(t);
            PotionEffect ex = p.getPotionEffect(t);
            boolean ours = ex != null && ex.isInfinite() && ex.isAmbient();
            if (amp == null) {
                if (ours) p.removePotionEffect(t);
                continue;
            }
            if (ours && ex.getAmplifier() == amp) continue;
            if (ours) {
                p.removePotionEffect(t);
            } else if (ex != null && ex.getAmplifier() >= amp) {
                continue;
            }
            p.addPotionEffect(new PotionEffect(t, -1, amp, true, false, true));
        }
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        if (inst != null) {
            AttributeModifier current = null;
            for (AttributeModifier m : inst.getModifiers()) {
                if (healthKey.equals(m.getKey())) {
                    current = m;
                    break;
                }
            }
            if (wantHealth && current == null) {
                inst.addTransientModifier(new AttributeModifier(healthKey, BONUS_HEALTH,
                        AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
                p.setHealth(Math.min(inst.getValue(), p.getHealth() + BONUS_HEALTH));
            } else if (!wantHealth && current != null) {
                inst.removeModifier(current);
            }
        }
    }

    // =====================================================================
    //  Menu
    // =====================================================================
    private void openMenu(Player p) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, 45, c("§8Arbre de compétences"));
        fill(m.inv, p);
        p.openInventory(m.inv);
    }

    private void fill(Inventory inv, Player p) {
        Data d = data(p);
        ItemStack pane = item(Material.GRAY_STAINED_GLASS_PANE, " ", null);
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, pane);
        for (Path pa : Path.values()) inv.setItem(pa.slot, pathItem(pa, d, p));
        for (Node n : NODES) inv.setItem(n.slot, nodeItem(n, d, p));

        Node ult = ultimate(d);
        if (ult != null && d.owned.contains(ult.id)) {
            inv.setItem(36, item(Material.BLAZE_ROD, "§6Recevoir le sceptre",
                    List.of("§7Capacité : §e" + ult.name,
                            "§7Clic droit avec le sceptre pour l'utiliser.",
                            "§7Un seul sceptre valide par joueur.")));
        } else {
            inv.setItem(36, item(Material.BARRIER, "§8Sceptre verrouillé",
                    List.of("§cDébloque la capacité finale de ta voie",
                            "§c(dernier palier) pour l'obtenir.")));
        }
        inv.setItem(40, item(Material.NETHER_STAR, "§bPoints : §f" + d.points, List.of(
                "§7Comment gagner des points :",
                "§f- Joueur tué : §e+4 §7(tués : " + d.playerKills + ")",
                "§f- Monstres : §e+2 §7tous les 150 (" + (d.kills % 150) + "/150)",
                "§f- Cultures mûres : §e+3 §7tous les 500 (" + (d.crops % 500) + "/500)",
                "§f- Diamant/Émeraude/Netherite : §e+4 §7tous les 25 (" + (d.ores % 25) + "/25)")));
        inv.setItem(44, item(Material.TNT, "§cRéinitialiser ma progression", List.of(
                "§7Te permet de changer de voie.",
                "§cTu perds la moitié des points dépensés.",
                "§eClique deux fois pour confirmer.")));
    }

    private ItemStack pathItem(Path pa, Data d, Player viewer) {
        String name;
        List<String> lore = new ArrayList<>();
        lore.add("§7" + pa.desc);
        lore.add("");
        if (pa.id.equals(d.path)) {
            name = "§a" + pa.label + " §7(choisie)";
            lore.add("§aC'est ta voie actuelle.");
        } else if (d.path == null) {
            name = "§e" + pa.label;
            lore.add("§aClic pour choisir cette voie");
            lore.add("§cUn seul choix possible !");
        } else {
            name = "§8" + pa.label;
            lore.add("§cTu as déjà choisi une autre voie.");
        }
        return item(pa.icon, name, lore, "path_" + pa.id, viewer);
    }

    private ItemStack nodeItem(Node n, Data d, Player viewer) {
        List<String> lore = new ArrayList<>();
        lore.add("§7" + n.desc);
        lore.add("");
        boolean chosen = d.path != null;
        boolean mine = n.path.id.equals(d.path);
        if (chosen && !mine) {
            lore.add("§cAutre voie.");
            return item(Material.BARRIER, "§8" + n.name, lore);
        }
        if (!chosen) {
            lore.add(n.cost == 0 ? "§fCoût : §agratuit" : "§fCoût : §e" + n.cost + " points");
            lore.add("§7Choisis d'abord cette voie.");
            return item(n.icon, "§7" + n.name, lore, n.id, viewer);
        }
        if (d.owned.contains(n.id)) {
            boolean off = d.off.contains(n.id);
            lore.add(off ? "§cDésactivée" : "§aActive");
            lore.add("§eClic pour " + (off ? "activer" : "désactiver"));
            return item(n.icon, (off ? "§c[OFF] " : "§a[ON] ") + n.name, lore, n.id, viewer);
        }
        Node prev = n.tier > 1 ? nodeOf(n.path, n.tier - 1) : null;
        if (prev != null && !d.owned.contains(prev.id)) {
            lore.add("§cDébloque d'abord : §f" + prev.name);
            return item(Material.BARRIER, "§8" + n.name, lore);
        }
        lore.add("§fCoût : §e" + n.cost + " points");
        lore.add(d.points >= n.cost ? "§aClic pour acheter" : "§cPas assez de points");
        return item(n.icon, "§e" + n.name, lore, n.id, viewer);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof Menu)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int slot = e.getSlot();
        Path pa = Path.bySlot(slot);
        Node n = nodeBySlot(slot);
        if (pa != null) choosePath(p, pa);
        else if (n != null) clickNode(p, n);
        else if (slot == 36) giveWand(p);
        else if (slot == 44) tryReset(p);
        applyEffects(p);
        fill(e.getView().getTopInventory(), p);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }

    private void choosePath(Player p, Path pa) {
        Data d = data(p);
        if (d.path != null) {
            p.sendMessage(c(pa.id.equals(d.path)
                    ? "§eC'est déjà ta voie."
                    : "§cTu as déjà choisi une voie. Réinitialise ta progression pour changer."));
            return;
        }
        d.path = pa.id;
        for (Node n : NODES) {
            if (n.path == pa && n.cost == 0) {
                d.owned.add(n.id);
                d.off.remove(n.id);
            }
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        p.sendMessage(c("§aTu as choisi la §e" + pa.label + "§a !"));
    }

    private void clickNode(Player p, Node n) {
        Data d = data(p);
        if (d.path == null) {
            p.sendMessage(c("§cChoisis d'abord une voie (ligne du haut)."));
            return;
        }
        if (!n.path.id.equals(d.path)) {
            p.sendMessage(c("§cTu as choisi une autre voie. Réinitialise ta progression pour changer."));
            return;
        }
        if (d.owned.contains(n.id)) {
            if (d.off.remove(n.id)) p.sendMessage(c("§a" + n.name + " activée."));
            else {
                d.off.add(n.id);
                p.sendMessage(c("§c" + n.name + " désactivée."));
            }
            p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 1.0f, 1.0f);
            return;
        }
        Node prev = n.tier > 1 ? nodeOf(n.path, n.tier - 1) : null;
        if (prev != null && !d.owned.contains(prev.id)) {
            p.sendMessage(c("§cDébloque d'abord : " + prev.name));
            return;
        }
        if (d.points < n.cost) {
            p.sendMessage(c("§cIl te faut " + n.cost + " points (tu en as " + d.points + ")."));
            return;
        }
        d.points -= n.cost;
        d.owned.add(n.id);
        d.off.remove(n.id);
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.2f);
        p.sendMessage(c("§aCompétence débloquée : §e" + n.name));
    }

    private void tryReset(Player p) {
        Data d = data(p);
        if (d.path == null && d.owned.isEmpty()) {
            p.sendMessage(c("§eTu n'as rien à réinitialiser."));
            return;
        }
        long now = System.currentTimeMillis();
        Long t = pendingReset.get(p.getUniqueId());
        if (t == null || now - t > 10000L) {
            pendingReset.put(p.getUniqueId(), now);
            p.sendMessage(c("§cClique encore sur le TNT dans les 10 secondes pour confirmer "
                    + "(tu perds la moitié des points dépensés)."));
            return;
        }
        pendingReset.remove(p.getUniqueId());
        int spent = 0;
        for (Node n : NODES) if (d.owned.contains(n.id)) spent += n.cost;
        int refund = spent / 2;
        d.points += refund;
        resetState(p, d);
        p.sendMessage(c("§aProgression réinitialisée. §7Points rendus : §e" + refund
                + " §7(sur " + spent + " dépensés)."));
    }

    private void resetState(Player p, Data d) {
        d.owned.clear();
        d.off.clear();
        d.path = null;
        d.wand = null;
        removeWands(p);
        endCamo(p, false);
    }

    // =====================================================================
    //  Points
    // =====================================================================
    private void addPoints(Player p, Data d, int n, String reason) {
        d.points += n;
        p.sendMessage(c("§b+" + n + " points de compétence ! §7(" + reason + ") Total : " + d.points + " - /skills"));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ";" + b.getX() + ";" + b.getY() + ";" + b.getZ();
    }

    @EventHandler
    public void onKill(EntityDeathEvent e) {
        Player k = e.getEntity().getKiller();
        if (k == null || k.equals(e.getEntity())) return;
        Data d = data(k);
        if (e.getEntity() instanceof Player) {
            d.playerKills++;
            addPoints(k, d, POINTS_PLAYER_KILL, "joueur tué");
        } else if (e.getEntity() instanceof Enemy) {
            d.kills++;
            if (d.kills % MOBS_PER_REWARD == 0) addPoints(k, d, POINTS_MOBS, "150 monstres tués");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (ORES.contains(e.getBlockPlaced().getType())) placed.add(key(e.getBlockPlaced()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        Block b = e.getBlock();
        Material m = b.getType();
        Player p = e.getPlayer();
        if (ORES.contains(m)) {
            boolean wasPlaced = placed.remove(key(b));
            if (wasPlaced || p.getGameMode() == GameMode.CREATIVE) return;
            Data d = data(p);
            d.ores++;
            if (d.ores % ORES_PER_REWARD == 0) addPoints(p, d, POINTS_ORES, "25 minerais minés");
        } else if (CROPS.contains(m)) {
            if (p.getGameMode() == GameMode.CREATIVE) return;
            if (b.getBlockData() instanceof Ageable a && a.getAge() == a.getMaximumAge()) {
                Data d = data(p);
                d.crops++;
                if (d.crops % CROPS_PER_REWARD == 0) addPoints(p, d, POINTS_CROPS, "500 cultures récoltées");
            }
        }
    }

    // =====================================================================
    //  Connexion / resource pack
    // =====================================================================
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        getServer().getScheduler().runTaskLater(this, () -> {
            if (p.isOnline()) sendPack(p);
        }, 20L);
    }

    private void sendPack(Player p) {
        if (pack == null || pack.url == null) return;
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(PACK_ID, URI.create(pack.url), pack.sha1Hex);
            ResourcePackRequest req = ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .prompt(c(getConfig().getString("resource-pack.prompt", "§6SkillTree")))
                    .required(getConfig().getBoolean("resource-pack.force", false))
                    .replace(true)
                    .build();
            p.sendResourcePacks(req);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("URL de resource pack invalide : " + pack.url);
        }
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        PlayerResourcePackStatusEvent.Status st = e.getStatus();
        if (st == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            packLoaded.add(id);
        } else if (st == PlayerResourcePackStatusEvent.Status.DECLINED
                || st == PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD) {
            packLoaded.remove(id);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        dashUses.remove(id);
        pendingReset.remove(id);
        packLoaded.remove(id);
        veilUntil.remove(id);
        hidden.remove(id);
        stillTicks.remove(id);
        lastPos.remove(id);
        save();
    }

    // =====================================================================
    //  Combat : Fureur du Wither + rupture du camouflage
    // =====================================================================
    @EventHandler(ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        endCamo(p, true);
        if (!(e.getEntity() instanceof LivingEntity target)) return;
        if (!active(data(p), N_WITHER)) return;
        if (Math.random() * 100.0 < WITHER_CHANCE_PERCENT) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.WITHER, 100, 1));
        }
    }

    // =====================================================================
    //  Sceptre : capacités actives
    // =====================================================================
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_AIR && e.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (!isWand(e.getItem())) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!isValidWand(p, e.getItem())) {
            p.sendActionBar(c("§cCe sceptre n'est pas valide pour toi."));
            return;
        }
        Data d = data(p);
        Node ult = ultimate(d);
        if (ult == null || ult.ability == null || !active(d, ult)) {
            p.sendActionBar(c("§cCapacité non débloquée ou désactivée (/skills)"));
            return;
        }
        switch (ult.ability) {
            case "dash" -> useDash(p);
            case "shock" -> useTimed(p, "shock", cfgInt("abilities.shock.cooldown", 30), this::abilityShock);
            case "bastion" -> useTimed(p, "bastion", cfgInt("abilities.bastion.cooldown", 45), this::abilityBastion);
            case "grow" -> useTimed(p, "grow", cfgInt("abilities.grow.cooldown", 20), this::abilityGrow);
            case "veil" -> useTimed(p, "veil", cfgInt("abilities.veil.cooldown", 40), this::abilityVeil);
            default -> {
            }
        }
    }

    private void useTimed(Player p, String ability, int seconds, Predicate<Player> action) {
        String k = p.getUniqueId() + ":" + ability;
        long now = System.currentTimeMillis();
        Long end = cooldowns.get(k);
        if (end != null && end > now) {
            p.sendActionBar(c("§cRecharge : " + ((end - now) / 1000L + 1L) + "s"));
            return;
        }
        if (action.test(p)) cooldowns.put(k, now + seconds * 1000L);
    }

    // --- Vitesse : Dash ---
    private void useDash(Player p) {
        int maxUses = cfgInt("abilities.dash.uses", 3);
        long window = cfgInt("abilities.dash.window", 30) * 1000L;
        long now = System.currentTimeMillis();
        Deque<Long> uses = dashUses.computeIfAbsent(p.getUniqueId(), k -> new ArrayDeque<>());
        while (!uses.isEmpty() && now - uses.peekFirst() > window) uses.pollFirst();
        if (!uses.isEmpty() && now - uses.peekLast() < DASH_DELAY_MS) return;
        if (uses.size() >= maxUses) {
            long wait = (uses.peekFirst() + window - now) / 1000L + 1L;
            p.sendActionBar(c("§cDash rechargé dans " + wait + "s"));
            return;
        }
        uses.addLast(now);
        Vector v = p.getLocation().getDirection().multiply(1.8);
        v.setY(Math.max(v.getY(), 0.0) + 0.25);
        p.setVelocity(v);
        p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 15);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 1.0f, 1.2f);
        p.sendActionBar(c("§aDash ! §7(" + (maxUses - uses.size()) + "/" + maxUses + " restants)"));
    }

    // --- Force : onde de choc ---
    private boolean abilityShock(Player p) {
        double r = cfgDouble("abilities.shock.radius", 5.0);
        double dmg = cfgDouble("abilities.shock.damage", 8.0);
        Location origin = p.getLocation();
        p.getWorld().spawnParticle(Particle.EXPLOSION, origin.clone().add(0, 1, 0), 1);
        p.getWorld().spawnParticle(Particle.SWEEP_ATTACK, origin.clone().add(0, 1, 0), 24, r / 2, 0.3, r / 2, 0);
        p.playSound(origin, Sound.ENTITY_GENERIC_EXPLODE, 1.0f, 0.8f);
        for (Entity en : p.getNearbyEntities(r, 2.5, r)) {
            if (!(en instanceof LivingEntity le) || en instanceof ArmorStand) continue;
            if (en instanceof Tameable t && t.isTamed()) continue;
            if (en instanceof Player other) {
                if (!p.getWorld().getPVP() || other.getGameMode() == GameMode.CREATIVE
                        || other.getGameMode() == GameMode.SPECTATOR) continue;
            }
            le.damage(dmg, p);
            Vector kb = le.getLocation().toVector().subtract(origin.toVector());
            kb.setY(0);
            if (kb.lengthSquared() < 1.0E-4) kb = new Vector(0, 0, 0.1);
            kb.normalize().multiply(1.3).setY(0.45);
            le.setVelocity(kb);
        }
        int secs = cfgInt("abilities.shock.strength-seconds", 8);
        p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, secs * 20, 2, false, true, true));
        p.sendActionBar(c("§cOnde de choc !"));
        return true;
    }

    // --- Résistance : bastion ---
    private boolean abilityBastion(Player p) {
        int res = cfgInt("abilities.bastion.resistance-seconds", 6);
        p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, res * 20, 2, false, true, true));
        p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 30, 0.5, 0.8, 0.5, 0.02);
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.8f, 1.4f);
        p.sendActionBar(c("§9Bastion activé !"));
        return true;
    }

    // --- Fermier : bénédiction de la terre ---
    private boolean abilityGrow(Player p) {
        int r = cfgInt("abilities.grow.radius", 6);
        Location center = p.getLocation();
        int grown = 0;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    Block b = center.getWorld().getBlockAt(center.getBlockX() + dx, center.getBlockY() + dy, center.getBlockZ() + dz);
                    if (!GROWABLE.contains(b.getType())) continue;
                    BlockData bd = b.getBlockData();
                    if (bd instanceof Ageable a && a.getAge() < a.getMaximumAge()) {
                        a.setAge(a.getMaximumAge());
                        b.setBlockData(a);
                        grown++;
                        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, b.getLocation().add(0.5, 0.5, 0.5),
                                4, 0.3, 0.3, 0.3, 0);
                    }
                }
            }
        }
        if (grown == 0) {
            p.sendActionBar(c("§eAucune culture à faire pousser autour de toi."));
            return false;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.6f);
        p.sendActionBar(c("§aBénédiction de la Terre : §e" + grown + " §acultures mûres !"));
        return true;
    }

    // --- Camouflage : voile d'ombre ---
    private boolean abilityVeil(Player p) {
        int secs = cfgInt("abilities.veil.duration", 12);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, secs * 20, 0, true, false, false));
        veilUntil.put(p.getUniqueId(), System.currentTimeMillis() + secs * 1000L);
        for (Entity en : p.getNearbyEntities(32, 16, 32)) {
            if (en instanceof Mob m && p.equals(m.getTarget())) m.setTarget(null);
        }
        p.getWorld().spawnParticle(Particle.SMOKE, p.getLocation().add(0, 1, 0), 40, 0.4, 0.8, 0.4, 0.02);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 0.6f);
        p.sendActionBar(c("§5Voile d'ombre : tu disparais..."));
        return true;
    }

    // =====================================================================
    //  Camouflage : passifs
    // =====================================================================
    private boolean isVeiled(Player p) {
        Long t = veilUntil.get(p.getUniqueId());
        return t != null && t > System.currentTimeMillis();
    }

    /** Rompt voile + camouflage passif. */
    private void endCamo(Player p, boolean message) {
        UUID id = p.getUniqueId();
        boolean was = veilUntil.remove(id) != null;
        if (hidden.remove(id)) was = true;
        stillTicks.put(id, 0);
        if (was) {
            p.removePotionEffect(PotionEffectType.INVISIBILITY);
            if (message) p.sendActionBar(c("§7Ton camouflage est rompu."));
        }
    }

    /** Toutes les 10 ticks : accroupi + immobile => invisible (palier 2). */
    private void tickCamo() {
        long now = System.currentTimeMillis();
        int need = cfgInt("abilities.stealth.still-seconds", 3) * 20;
        for (Player p : getServer().getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            Long vu = veilUntil.get(id);
            if (vu != null && vu <= now) veilUntil.remove(id);
            Location cur = p.getLocation();
            Location last = lastPos.put(id, cur);
            if (isVeiled(p)) continue;

            boolean can = active(data(p), N_CAMO2);
            boolean still = p.isSneaking() && last != null && last.getWorld() == cur.getWorld()
                    && last.distanceSquared(cur) < 0.04;
            if (can && still) {
                int t = stillTicks.merge(id, 10, Integer::sum);
                if (t >= need) {
                    if (hidden.add(id)) p.sendActionBar(c("§8Tu te fonds dans l'ombre."));
                    p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 40, 0, true, false, false));
                }
            } else {
                stillTicks.put(id, 0);
                if (hidden.remove(id)) p.removePotionEffect(PotionEffectType.INVISIBILITY);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent e) {
        if (!(e.getTarget() instanceof Player p)) return;
        if (isVeiled(p)) {
            e.setCancelled(true);
            return;
        }
        Location a = e.getEntity().getLocation();
        Location b = p.getLocation();
        if (a.getWorld() != b.getWorld()) return;
        double dist = a.distance(b);
        Data d = data(p);
        if (hidden.contains(p.getUniqueId()) && active(d, N_CAMO2) && dist > 2.5) {
            e.setCancelled(true);
            return;
        }
        double sneakRange = cfgInt("abilities.stealth.sneak-detect", 6);
        if (p.isSneaking() && active(d, N_CAMO1) && dist > sneakRange) e.setCancelled(true);
    }

    // =====================================================================
    //  Anti-craft du sceptre
    // =====================================================================
    @EventHandler
    public void onCraft(PrepareItemCraftEvent e) {
        for (ItemStack st : e.getInventory().getMatrix()) {
            if (isWand(st)) {
                e.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler
    public void onFuel(FurnaceBurnEvent e) {
        if (isWand(e.getFuel())) e.setCancelled(true);
    }

    // =====================================================================
    //  Commandes
    // =====================================================================
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("skills.admin")) {
                sender.sendMessage(c("§cTu n'as pas la permission."));
                return true;
            }
            reloadConfig();
            if (pack != null) pack.stop();
            pack = new PackHost(this);
            if (!pack.start()) pack.url = null;
            sender.sendMessage(c("§aConfiguration rechargée."));
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("reset"))) {
            if (!sender.hasPermission("skills.admin")) {
                sender.sendMessage(c("§cTu n'as pas la permission."));
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(c("§c/skills give <joueur> <nombre> | /skills reset <joueur>"));
                return true;
            }
            Player t = Bukkit.getPlayerExact(args[1]);
            if (t == null) {
                sender.sendMessage(c("§cJoueur introuvable."));
                return true;
            }
            Data d = data(t);
            if (args[0].equalsIgnoreCase("give")) {
                int n;
                try {
                    n = Integer.parseInt(args.length > 2 ? args[2] : "1");
                } catch (NumberFormatException ex) {
                    sender.sendMessage(c("§cNombre invalide."));
                    return true;
                }
                d.points += n;
                sender.sendMessage(c("§a" + n + " point(s) donné(s) à " + t.getName() + "."));
                t.sendMessage(c("§b+" + n + " point(s) de compétence !"));
            } else {
                int spent = 0;
                for (Node n : NODES) if (d.owned.contains(n.id)) spent += n.cost;
                d.points += spent;
                resetState(t, d);
                applyEffects(t);
                sender.sendMessage(c("§aProgression de " + t.getName() + " réinitialisée (" + spent + " points rendus)."));
            }
            return true;
        }
        if (!(sender instanceof Player p)) {
            sender.sendMessage(c("§cCommande réservée aux joueurs."));
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "";
        switch (sub) {
            case "wand" -> giveWand(p);
            case "pack" -> {
                if (pack == null || pack.url == null) p.sendMessage(c("§cLe resource pack n'est pas disponible."));
                else {
                    sendPack(p);
                    p.sendMessage(c("§7Envoi du resource pack..."));
                }
            }
            case "points" -> {
                Data d = data(p);
                p.sendMessage(c("§bPoints : §f" + d.points + " §7| Joueurs : §f" + d.playerKills
                        + " §7| Monstres : §f" + (d.kills % MOBS_PER_REWARD) + "/" + MOBS_PER_REWARD
                        + " §7| Cultures : §f" + (d.crops % CROPS_PER_REWARD) + "/" + CROPS_PER_REWARD
                        + " §7| Minerais : §f" + (d.ores % ORES_PER_REWARD) + "/" + ORES_PER_REWARD));
            }
            default -> openMenu(p);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            List<String> subs = new ArrayList<>(List.of("wand", "points", "pack"));
            if (sender.hasPermission("skills.admin")) subs.addAll(List.of("give", "reset", "reload"));
            for (String s : subs) if (s.startsWith(args[0].toLowerCase())) out.add(s);
        } else if (args.length == 2 && sender.hasPermission("skills.admin")
                && (args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("reset"))) {
            for (Player pl : Bukkit.getOnlinePlayers()) {
                if (pl.getName().toLowerCase().startsWith(args[1].toLowerCase())) out.add(pl.getName());
            }
        }
        return out;
    }

    // =====================================================================
    //  Types
    // =====================================================================
    static class Node {
        final String id;
        final String name;
        final String desc;
        final Path path;
        final int tier;
        final int cost;
        final int slot;
        final Material icon;
        final Map<PotionEffectType, Integer> effects = new LinkedHashMap<>();
        boolean health;
        boolean wither;
        String ability;

        Node(String id, Path path, int tier, int cost, Material icon, String name, String desc) {
            this.id = id;
            this.path = path;
            this.tier = tier;
            this.cost = cost;
            this.icon = icon;
            this.name = name;
            this.desc = desc;
            this.slot = 9 * tier + path.slot;
        }

        Node eff(PotionEffectType t, int amp) {
            effects.put(t, amp);
            return this;
        }

        Node health() {
            health = true;
            return this;
        }

        Node wither() {
            wither = true;
            return this;
        }

        Node ability(String a) {
            ability = a;
            return this;
        }
    }

    enum Path {
        SPEED("speed", "Voie de la Vitesse", Material.SUGAR, 0, "Cours plus vite, fonce avec le dash."),
        FORCE("force", "Voie de la Force", Material.IRON_SWORD, 2, "Frappe plus fort, brise tes ennemis."),
        RESIST("resist", "Voie de la Résistance", Material.SHIELD, 4, "Encaisse les coups, résiste au feu."),
        FARM("farm", "Voie du Fermier", Material.WHEAT, 6, "Travaille vite, fais pousser la terre."),
        CAMO("camo", "Voie du Camouflage", Material.ENDER_EYE, 8, "Fonds-toi dans l'ombre, disparais.");

        final String id;
        final String label;
        final String desc;
        final Material icon;
        final int slot;

        Path(String id, String label, Material icon, int slot, String desc) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.slot = slot;
            this.desc = desc;
        }

        static Path byId(String id) {
            for (Path p : values()) if (p.id.equals(id)) return p;
            return null;
        }

        static Path bySlot(int slot) {
            for (Path p : values()) if (p.slot == slot) return p;
            return null;
        }
    }

    static class Data {
        int points;
        int kills;
        int playerKills;
        int crops;
        int ores;
        String path;
        String wand;
        Set<String> owned = new HashSet<>();
        Set<String> off = new HashSet<>();
    }

    static class Menu implements InventoryHolder {
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }
}
