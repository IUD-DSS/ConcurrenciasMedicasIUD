package org.example;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicLong;

// ===== Dominio =====
enum Severity { CRITICO, GRAVE, MODERADO, LEVE }
enum IncidentStatus { PENDING, ASSIGNED, IN_PROGRESS, RESOLVED }
enum ResourceStatus { AVAILABLE, EN_ROUTE, BUSY, OFFLINE }
record Location(double lat, double lon) {}

final class Incident {
    final UUID id = UUID.randomUUID();
    final Severity severity;
    final Location location;
    final long createdAt = System.currentTimeMillis();
    final String description;
    final AtomicReference<IncidentStatus> status = new AtomicReference<>(IncidentStatus.PENDING);

    Incident(Severity severity, Location location, String description) {
        this.severity = severity;
        this.location = location;
        this.description = description;
    }
    public String toString() {
        return "Incident{" + id + ", " + severity + ", " + description + "}";
    }
}

// ===== Priorización =====
interface PriorityStrategy {
    double score(Incident incident, long now);
}

final class WeightedPriorityStrategy implements PriorityStrategy {
    private final double ws, wt, wd;
    private final Function<Double, Double> fWait;
    private final Function<Double, Double> gDist;
    private final Location depot;

    WeightedPriorityStrategy(double ws, double wt, double wd,
                             Function<Double, Double> fWait,
                             Function<Double, Double> gDist,
                             Location depot) {
        this.ws = ws; this.wt = wt; this.wd = wd;
        this.fWait = fWait; this.gDist = gDist; this.depot = depot;
    }
    public double score(Incident i, long now) {
        double sev = switch (i.severity) {
            case CRITICO -> 100; case GRAVE -> 70; case MODERADO -> 40; case LEVE -> 10;
        };
        double waitMin = (now - i.createdAt) / 60000.0;
        double distKm = Geo.haversineKm(depot, i.location);
        return ws * sev + wt * fWait.apply(waitMin) + wd * gDist.apply(distKm);
    }
}

final class Geo {
    static double haversineKm(Location a, Location b) {
        double R = 6371.0;
        double dLat = Math.toRadians(b.lat() - a.lat());
        double dLon = Math.toRadians(b.lon() - a.lon());
        double lat1 = Math.toRadians(a.lat());
        double lat2 = Math.toRadians(b.lat());
        double h = Math.sin(dLat/2)*Math.sin(dLat/2)
                + Math.cos(lat1)*Math.cos(lat2)*Math.sin(dLon/2)*Math.sin(dLon/2);
        return 2 * R * Math.asin(Math.sqrt(h));
    }
}

// ===== Recursos =====
final class Resource {
    final UUID id = UUID.randomUUID();
    final String name;
    final ReentrantLock lock = new ReentrantLock();
    final AtomicReference<ResourceStatus> status = new AtomicReference<>(ResourceStatus.AVAILABLE);
    volatile Location location;

    Resource(String name, Location location) {
        this.name = name;
        this.location = location;
    }

    boolean tryReserve(Duration timeout) throws InterruptedException {
        if (lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            try {
                if (status.get() == ResourceStatus.AVAILABLE) {
                    status.set(ResourceStatus.BUSY);
                    return true;
                }
            } finally {
                lock.unlock();
            }
        }
        return false;
    }

    void release() {
        lock.lock();
        try {
            status.set(ResourceStatus.AVAILABLE);
        } finally {
            lock.unlock();
        }
    }
}

final class ResourceBundle {
    final Resource ambulance;
    final Resource medic;
    final Resource equipment;
    ResourceBundle(Resource ambulance, Resource medic, Resource equipment) {
        this.ambulance = ambulance; this.medic = medic; this.equipment = equipment;
    }
    List<Resource> all() { return List.of(ambulance, medic, equipment); }
}

// ===== Gestor de recursos =====
final class ResourceManager {
    private final ConcurrentHashMap<UUID, Resource> ambulances = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Resource> medics = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Resource> equipment = new ConcurrentHashMap<>();

    void addAmbulance(Resource r) { ambulances.put(r.id, r); }
    void addMedic(Resource r) { medics.put(r.id, r); }
    void addEquipment(Resource r) { equipment.put(r.id, r); }

    Optional<ResourceBundle> tryReserve(Incident i, Duration timeout) throws InterruptedException {
        // Orden de bloqueo consistente: ambulancia -> médico -> equipo
        Resource amb = pickAvailableClosest(ambulances.values(), i.location).orElse(null);
        Resource med = pickAvailableClosest(medics.values(), i.location).orElse(null);
        Resource eqp = pickAvailableClosest(equipment.values(), i.location).orElse(null);

        if (amb == null || med == null || eqp == null) return Optional.empty();

        long perLockMs = Math.max(50, timeout.toMillis() / 3);
        if (!amb.tryReserve(Duration.ofMillis(perLockMs))) return Optional.empty();
        try {
            if (!med.tryReserve(Duration.ofMillis(perLockMs))) {
                amb.release(); return Optional.empty();
            }
            try {
                if (!eqp.tryReserve(Duration.ofMillis(perLockMs))) {
                    med.release(); amb.release(); return Optional.empty();
                }
                return Optional.of(new ResourceBundle(amb, med, eqp));
            } catch (Exception e) {
                med.release(); amb.release(); throw e;
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            amb.release();
            return Optional.empty();
        }
    }

    void release(ResourceBundle bundle) {
        bundle.equipment.release();
        bundle.medic.release();
        bundle.ambulance.release();
    }

    int availableAmbulances() { return countAvailable(ambulances.values()); }
    int availableMedics() { return countAvailable(medics.values()); }
    int availableEquipment() { return countAvailable(equipment.values()); }

    private int countAvailable(Collection<Resource> resources) {
        int c = 0;
        for (Resource r : resources) if (r.status.get() == ResourceStatus.AVAILABLE) c++;
        return c;
    }

    private Optional<Resource> pickAvailableClosest(Collection<Resource> set, Location loc) {
        Resource best = null; double bestDist = Double.MAX_VALUE;
        for (Resource r : set) {
            if (r.status.get() != ResourceStatus.AVAILABLE) continue;
            double d = Geo.haversineKm(r.location, loc);
            if (d < bestDist) { bestDist = d; best = r; }
        }
        return Optional.ofNullable(best);
    }
}

// ===== Eventos y bus =====
interface Event {}

final class EventBus {
    private final CopyOnWriteArrayList<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();
    void subscribe(Consumer<Event> c) { subscribers.add(c); }
    void publish(Event e) { for (var s : subscribers) s.accept(e); }
}

record IncidentCreated(Incident incident) implements Event {}
record ResourcesAssigned(UUID incidentId, ResourceBundle bundle) implements Event {}
record UnitStatus(UUID incidentId, String unitName, String status) implements Event {}
record IncidentResolved(UUID incidentId, long responseMs) implements Event {}
record AlertFailure(String message) implements Event {}

// ===== Cola de incidentes =====
final class IncidentQueue {
    private final PriorityBlockingQueue<Incident> queue;
    private final PriorityStrategy strategy;
    IncidentQueue(PriorityStrategy strategy) {
        this.strategy = strategy;
        Comparator<Incident> cmp = (a, b) -> Double.compare(
                strategy.score(b, System.currentTimeMillis()),
                strategy.score(a, System.currentTimeMillis()));
        this.queue = new PriorityBlockingQueue<>(128, cmp);
    }
    void submit(Incident i) { queue.put(i); }
    Incident take() throws InterruptedException { return queue.take(); }
    int size() { return queue.size(); }
}

// ===== Despachador =====
final class Dispatcher implements Runnable {
    private final IncidentQueue incidents;
    private final ResourceManager rm;
    private final EventBus bus;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    Dispatcher(IncidentQueue incidents, ResourceManager rm, EventBus bus) {
        this.incidents = incidents; this.rm = rm; this.bus = bus;
    }

    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Incident i = incidents.take();
                Optional<ResourceBundle> bundle = rm.tryReserve(i, Duration.ofSeconds(2));
                if (bundle.isPresent()) {
                    i.status.set(IncidentStatus.ASSIGNED);
                    // Imprime gravedad y ubicación
                    System.out.printf("[Asignado] Incidente %s | Gravedad: %s | Ubicación: (%.5f, %.5f)%n",
                            i.id, i.severity, i.location.lat(), i.location.lon());
                    bus.publish(new ResourcesAssigned(i.id, bundle.get()));
                    executeOrder(i, bundle.get());
                } else {
                    bus.publish(new AlertFailure("No resources for " + i.id));
                    // Backoff y reencolar
                    Thread.sleep(200);
                    incidents.submit(i);
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        scheduler.shutdownNow();
    }

    private void executeOrder(Incident i, ResourceBundle bundle) {
        // Simulación de progreso: en ruta -> en escena -> hospital -> resuelto
        long start = System.currentTimeMillis();
        bus.publish(new UnitStatus(i.id, bundle.ambulance.name, "EN_ROUTE"));
        bus.publish(new UnitStatus(i.id, bundle.medic.name, "EN_ROUTE"));
        bus.publish(new UnitStatus(i.id, bundle.equipment.name, "EN_ROUTE"));

        scheduler.schedule(() -> {
            i.status.set(IncidentStatus.IN_PROGRESS);
            bus.publish(new UnitStatus(i.id, bundle.ambulance.name, "AT_SCENE"));
            bus.publish(new UnitStatus(i.id, bundle.medic.name, "AT_SCENE"));
            bus.publish(new UnitStatus(i.id, bundle.equipment.name, "AT_SCENE"));
        }, 1, TimeUnit.SECONDS);

        scheduler.schedule(() -> {
            bus.publish(new UnitStatus(i.id, bundle.ambulance.name, "AT_HOSPITAL"));
            bus.publish(new UnitStatus(i.id, bundle.medic.name, "AT_HOSPITAL"));
            bus.publish(new UnitStatus(i.id, bundle.equipment.name, "AT_HOSPITAL"));
        }, 3, TimeUnit.SECONDS);

        scheduler.schedule(() -> {
            i.status.set(IncidentStatus.RESOLVED);
            long resp = System.currentTimeMillis() - start;
            bus.publish(new IncidentResolved(i.id, resp));
            rm.release(bundle);
        }, 5, TimeUnit.SECONDS);
    }
}

// ===== Operador (productor de incidentes) =====
final class Operator implements Runnable {
    private final IncidentQueue queue;
    private final String source;
    private final EventBus bus;
    private final Random rnd = new Random();
    private final Location cityCenter;

    Operator(IncidentQueue q, String source, EventBus bus, Location cityCenter) {
        this.queue = q; this.source = source; this.bus = bus; this.cityCenter = cityCenter;
    }

    public void run() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                Incident i = randomIncident();
                queue.submit(i);
                bus.publish(new IncidentCreated(i));
                Thread.sleep(300 + rnd.nextInt(400)); // ritmo de llegada
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
    private String direccionCardinal(Location base, Location loc) {
        double dLat = loc.lat() - base.lat();
        double dLon = loc.lon() - base.lon();

        // Si está prácticamente en el centro
        if (Math.abs(dLat) < 1e-5 && Math.abs(dLon) < 1e-5) return "centro";

        // Decidir cuál desplazamiento es mayor (latitud vs longitud)
        if (Math.abs(dLat) > Math.abs(dLon)) {
            return dLat > 0 ? "norte" : "sur";
        } else {
            return dLon > 0 ? "este" : "oeste";
        }
    }

    private Incident randomIncident() {
        Severity s = Severity.values()[rnd.nextInt(Severity.values().length)];
        Location loc = jitter(cityCenter, rnd.nextDouble() * 0.05, rnd.nextDouble() * 0.05);
        String desc = source + " reporte " + switch (s) {
            case CRITICO -> "Paro cardiorrespiratorio";
            case GRAVE -> "Accidente de tránsito";
            case MODERADO -> "Trauma leve";
            case LEVE -> "Fiebre persistente";
        };
        System.out.printf("[Nuevo incidente] Gravedad: %s | Ubicación: (%.5f, %.5f) [%s] | Descripción: %s%n",
                s, loc.lat(), loc.lon(), direccionCardinal(cityCenter, loc), desc);
        return new Incident(s, loc, desc);
    }

    private Location jitter(Location cityCenter, double dLat, double dLon) {
        return new Location(
                cityCenter.lat() + (dLat - 0.025),  // variación en latitud
                cityCenter.lon() + (dLon - 0.025)   // variación en longitud
        );
    }

}

// ===== Monitor de consola =====
final class Metrics {
    private final LongAdder resolved = new LongAdder();
    private final LongAdder totalResponseMs = new LongAdder();
    private final LongAdder created = new LongAdder();

    void markCreated() { created.increment(); }
    void markResolved(long ms) { resolved.increment(); totalResponseMs.add(ms); }
    int totalCreated() { return created.intValue(); }
    int totalResolved() { return resolved.intValue(); }
    double avgResponseSeconds() {
        int r = resolved.intValue();
        return r == 0 ? 0.0 : totalResponseMs.doubleValue() / r / 1000.0;
    }
}

final class ConsoleMonitor {
    private final Metrics metrics;
    private final IncidentQueue queue;
    private final ResourceManager rm;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    ConsoleMonitor(EventBus bus, Metrics metrics, IncidentQueue queue, ResourceManager rm) {
        this.metrics = metrics; this.queue = queue; this.rm = rm;
        bus.subscribe(e -> {
            if (e instanceof IncidentCreated ic) metrics.markCreated();
            else if (e instanceof IncidentResolved ir) metrics.markResolved(ir.responseMs());
            else if (e instanceof AlertFailure af) System.out.println("[ALERTA] " + af.message());
        });
    }

    void start() {
        scheduler.scheduleAtFixedRate(() -> {
            System.out.printf(
                    "[Monitor] Pendientes=%d | Resueltos=%d/%d | RespAvg=%.1fs | AmbDisp=%d | MedDisp=%d | EqDisp=%d%n",
                    queue.size(),
                    metrics.totalResolved(), metrics.totalCreated(),
                    metrics.avgResponseSeconds(),
                    rm.availableAmbulances(), rm.availableMedics(), rm.availableEquipment()
            );
        }, 0, 2, TimeUnit.SECONDS);
    }
    void stop() { scheduler.shutdownNow(); }
}

// ===== Bootstrap de flota =====
final class Bootstrap {
    static void createFleet(ResourceManager rm, int amb, int med, int eqp, Location base) {
        for (int i = 1; i <= amb; i++) rm.addAmbulance(new Resource("Ambulancia-" + i, base));
        for (int i = 1; i <= med; i++) rm.addMedic(new Resource("Medico-" + i, base));
        for (int i = 1; i <= eqp; i++) rm.addEquipment(new Resource("Equipo-" + i, base));
    }
}
public class Main {
    public static void main(String[] args) {
        // Punto de referencia: Sabaneta, Antioquia aprox
        Location base = new Location(6.150, -75.615);

        PriorityStrategy strategy = new WeightedPriorityStrategy(
                0.6, 0.3, 0.1,
                x -> Math.min(100, x * 5.0),          // crece con el tiempo de espera
                d -> 1.0 / (1.0 + d),                  // más cerca, mayor puntaje
                base
        );

        IncidentQueue incidentQueue = new IncidentQueue(strategy);
        ResourceManager rm = new ResourceManager();
        Bootstrap.createFleet(rm, 8, 12, 6, base);

        EventBus bus = new EventBus();
        Metrics metrics = new Metrics();
        ConsoleMonitor monitor = new ConsoleMonitor(bus, metrics, incidentQueue, rm);
        monitor.start();

        // Operadores
        ExecutorService operators = Executors.newFixedThreadPool(2);
        operators.submit(new Operator(incidentQueue, "Linea-123", bus, base));
        operators.submit(new Operator(incidentQueue, "Linea-124", bus, base));

        // Despachadores
        ExecutorService dispatchers = Executors.newFixedThreadPool(2);
        dispatchers.submit(new Dispatcher(incidentQueue, rm, bus));
        dispatchers.submit(new Dispatcher(incidentQueue, rm, bus));

        // Finalización controlada
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            operators.shutdownNow();
            dispatchers.shutdownNow();
            monitor.stop();
            System.out.println("Sistema detenido.");
        }));

        // Simulación por tiempo fijo (ej. 30 segundos), luego detener
        try {
            Thread.sleep(30_000);
        } catch (InterruptedException ignored) {}
        operators.shutdownNow();
        dispatchers.shutdownNow();
        monitor.stop();
        System.out.println("Simulación finalizada.");
    }
}

// ===== Utilidad concurrente =====
final class LongAdder {
    private final AtomicLong value = new AtomicLong(0);

    void add(long x) {
        value.addAndGet(x);
    }

    void increment() {
        add(1);
    }

    int intValue() {
        return value.intValue();
    }

    long longValue() {
        return value.longValue();
    }

    double doubleValue() {
        return value.doubleValue();
    }

    ;

}


