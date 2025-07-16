package org.example.windirtrash.utils;

import java.util.Map;

public final class CategoryInfo {
    private CategoryInfo() {
    }

    public enum Risk {SAFE, REVIEW}

    public record Meta(String desc, Risk risk) {
    }

    public static final Map<String, Meta> META = Map.ofEntries(
            Map.entry("Archivos temporales",
                    new Meta("Ficheros .tmp, .temp… creados sólo de forma momentánea.", Risk.SAFE)),
            Map.entry("Archivos log",
                    new Meta("Registros de actividad (.log) útiles para depurar.", Risk.SAFE)),
            Map.entry("Copias de seguridad",
                    new Meta("Ficheros .bak, .old, ~… Se conserva la copia más reciente; el resto se pueden borrar.", Risk.REVIEW)),
            Map.entry("Volcados (dumps)",
                    new Meta("Ficheros .dmp generados al fallar un programa.", Risk.REVIEW)),
            Map.entry("Carpetas de caché",
                    new Meta("Datos que los programas pueden reconstruir (temp, __pycache__, etc.).", Risk.SAFE)),
            Map.entry("node_modules",
                    new Meta("Dependencias descargadas por proyectos Node.js.", Risk.SAFE)),
            Map.entry("Miniaturas del sistema",
                    new Meta("Thumbs.db, .DS_Store… Archivos de metadatos creados por el SO.", Risk.SAFE)),
            Map.entry("Objetos compilados",
                    new Meta(".class, .o, target/, build/… Productos intermedios de compilación.", Risk.SAFE)),
            Map.entry("Archivos vacíos",
                    new Meta("Ficheros de 0B.", Risk.SAFE)),
            Map.entry("Carpetas vacías",
                    new Meta("Directorios sin contenido.", Risk.SAFE)),
            Map.entry("Atajos rotos",
                    new Meta("Accesos directos cuyos destinos ya no existen.", Risk.REVIEW)),  // <-- ahora REVIEW
            Map.entry("Otros",
                    new Meta("Archivos que no encajan en las categorías anteriores.", Risk.REVIEW))
    );

    public static Meta get(String cat) {
        return META.getOrDefault(cat, new Meta("Sin descripción.", Risk.REVIEW));
    }
}
