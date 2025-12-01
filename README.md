# Concurrencias Médicas IUD 🏥💻

Repositorio del proyecto **“Sistema de gestión de emergencias médicas con concurrencia”** desarrollado para la asignatura **Desarrollo de Software Seguro** (IU Digital de Antioquia).

El sistema simula una central de despacho que:

- Recibe incidentes de diferentes niveles de severidad.  
- Los prioriza según severidad, tiempo de espera y distancia.  
- Asigna recursos (ambulancias, médicos y equipos) de forma concurrente.  
- Registra métricas como tiempos de respuesta y número de incidentes atendidos.  

---

## 🧑‍🤝‍🧑 Integrantes

- Juan Guillermo Osorio Gómez  
- Cristian Felipe Vargas Sánchez  
- Juan David Marriaga Pertuz  

**Docente:** Jorge Armando Julio  

---

## 🎥 Videos de explicación del proyecto

Cada integrante tiene su propio video explicando el proyecto, la concurrencia y las decisiones de seguridad.

- [🎬 Video de Juan Guillermo](https://www.youtube.com/watch?v=BW91SF7ZFgI )
- [🎬 Video de Cristian Felipe](https://youtu.be/VIDEO_CRISTIAN_FELIPE)
- [🎬 Video de Juan David](https://youtu.be/VIDEO_JUAN_DAVID)


---

## 📘 Manual Técnico

El detalle completo de la arquitectura, patrones de concurrencia, análisis de rendimiento y consideraciones de seguridad se encuentra en el manual técnico en PDF.

👉 **Abrir manual técnico:**  
[📄 Manual Técnico actividad 2 DSS](./Manual_Tecnico_actividad_2_dss.pdf)

---

## ⚙️ Tecnologías utilizadas

- **Lenguaje:** Java 17+  
- **Paradigma:** Programación concurrente  
- **Librerías estándar utilizadas:**
  - `java.util.concurrent`  
  - `PriorityBlockingQueue`  
  - `ExecutorService` / `ScheduledExecutorService`  
  - `ReentrantLock`  
  - Tipos atómicos (`AtomicReference`)  
  - `CopyOnWriteArrayList` y otras colecciones concurrentes  

---

## ▶️ Cómo ejecutar el proyecto

1. **Clonar el repositorio**

    git clone https://github.com/IUD-DSS/ConcurrenciasMedicasIUD.git  
    cd ConcurrenciasMedicasIUD

2. **Compilar con Maven**

    mvn clean package

3. **Ejecutar la aplicación**

   - Si el proyecto genera un JAR ejecutable (ajusta el nombre según tu `pom.xml`):

        java -jar target/concurrencias-medicas-iud-1.0-SNAPSHOT.jar

   - O si se ejecuta directamente desde la clase `Main`:

        mvn exec:java -Dexec.mainClass="paquete.Main"

   (Cambia `paquete.Main` por el paquete+clase real donde tengas tu método `public static void main`).

---

## 🧩 Descripción rápida de la arquitectura

- **`Incident` / `Severity` / `IncidentStatus`**  
  Modelan los incidentes de emergencia (nivel de severidad, ubicación, estado, hora de creación).

- **`IncidentQueue`**  
  Cola priorizada de incidentes basada en `PriorityBlockingQueue` que implementa el patrón **Productor–Consumidor**.

- **`PriorityStrategy` / `WeightedPriorityStrategy`**  
  Calculan la prioridad combinando severidad, tiempo de espera y distancia al centro de operaciones.

- **`Resource` / `ResourceManager`**  
  Gestionan recursos como ambulancias, médicos y equipos.  
  Usan `ReentrantLock` y estados atómicos para reservar y liberar recursos de forma segura.

- **`Dispatcher`**  
  Hilos consumidores que toman incidentes de la cola, intentan asignar recursos y simulan el ciclo de atención  
  (en ruta → en escena → en hospital) mediante `ScheduledExecutorService`.

- **`Operator`**  
  Hilos productores que generan incidentes aleatorios, simulando las llamadas que llegan a la central de emergencias.

- **`EventBus` + `ConsoleMonitor` + `Metrics`**  
  Implementan un patrón de **publicación/suscripción**: los componentes publican eventos y el monitor de consola  
  los escucha para mostrar métricas y estados del sistema en tiempo real.

---

## 🛡️ Enfoque de seguridad y concurrencia

- Uso de `ReentrantLock` con **orden jerárquico** (Ambulancia → Médico → Equipo) para evitar interbloqueos al reservar múltiples recursos.  
- Uso de **tipos atómicos** (`AtomicReference` y contadores concurrentes) para mantener la integridad de estados compartidos sin bloqueos pesados.  
- Uso de `tryLock` con **timeout** para evitar bloqueos indefinidos y mejorar la disponibilidad del sistema.  
- Estrategia de prioridad que incluye el **tiempo de espera**, evitando starvation de incidentes de menor severidad.  
  
---

## 📌 Notas finales

Este repositorio corresponde a la **Actividad 2** de la materia **Desarrollo de Software Seguro**, y sirve como ejemplo práctico de:

- Diseño de sistemas concurrentes.  
- Manejo explícito de sincronización y colas de prioridad.  
- Aplicación de principios básicos de software seguro en el contexto de concurrencia.  
