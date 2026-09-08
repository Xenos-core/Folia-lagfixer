package xyz.lychee.lagfixer.objects;

import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.*;
import xyz.lychee.lagfixer.managers.SupportManager;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

@Getter
public class WorldsMonitor extends AbstractMonitor {
    private volatile int entities = 0;
    private volatile int creatures = 0;
    private volatile int items = 0;
    private volatile int projectiles = 0;
    private volatile int vehicles = 0;
    private volatile int tiles = 0;
    private volatile int chunks = 0;

    public WorldsMonitor() {
        super(false, "worlds");
    }

    @Override
    public void run() {
        ISupportNms nms = SupportManager.getInstance().getNms();

        // getEntities(), getLoadedChunks() and getTileEntitiesCount() touch chunk/entity
        // state that on Folia is owned by each world's region thread — illegal on the
        // global thread. So we dispatch one region task per world and aggregate the
        // counts. The monitor is periodic (a few seconds), so blocking the global
        // thread briefly to collect the per-world results is acceptable.
        List<CompletableFuture<int[]>> futures = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Location at = world.getSpawnLocation();
            CompletableFuture<int[]> f = new CompletableFuture<>();
            futures.add(f);
            SupportManager.getInstance().getFork().runNow(false, at, () -> {
                int entities = 0, creatures = 0, items = 0, projectiles = 0, vehicles = 0, tiles = 0, chunks = 0;
                Chunk[] loaded = world.getLoadedChunks();
                List<Entity> list = world.getEntities();
                entities = list.size();
                chunks = loaded.length;

                for (Entity entity : list) {
                    if (entity instanceof Mob) {
                        creatures++;
                    } else if (entity instanceof Vehicle) {
                        vehicles++;
                    } else if (entity instanceof Item) {
                        items++;
                    } else if (entity instanceof Projectile) {
                        projectiles++;
                    }
                }

                for (Chunk chunk : loaded) {
                    tiles += nms.getTileEntitiesCount(chunk);
                }

                f.complete(new int[]{entities, creatures, items, projectiles, vehicles, tiles, chunks});
            });
        }

        int entities = 0, creatures = 0, items = 0, projectiles = 0, vehicles = 0, tiles = 0, chunks = 0;
        for (CompletableFuture<int[]> f : futures) {
            try {
                int[] r = f.get();
                entities += r[0];
                creatures += r[1];
                items += r[2];
                projectiles += r[3];
                vehicles += r[4];
                tiles += r[5];
                chunks += r[6];
            } catch (InterruptedException | ExecutionException ignored) {
            }
        }

        this.entities = entities;
        this.creatures = creatures;
        this.items = items;
        this.projectiles = projectiles;
        this.vehicles = vehicles;
        this.tiles = tiles;
        this.chunks = chunks;
    }
}
