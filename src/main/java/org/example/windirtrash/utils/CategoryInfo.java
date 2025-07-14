package org.example.windirtrash.utils;

import java.util.Map;

public final class CategoryInfo {
    private CategoryInfo() {
    }

    /**
     * Nombre de categoría → descripción legible.
     */
    public static final Map<String, String> DESC = Map.ofEntries(
            Map.entry("Archivos temporales",
                    "Ficheros .tmp, .temp… creados solo para uso momentáneo por programas. SEGURO ELIMINAR"),
            Map.entry("Archivos log",
                    "Registros de actividad (.log) útiles para depurar. SEGURO ELIMINAR"),
            Map.entry("Copias de seguridad",
                    ".bak, .old… Copias automáticas; conserva la más reciente si dudas."),
            Map.entry("Volcados (dumps)",
                    "Ficheros .dmp generados cuando un programa falla; ocupan mucho espacio. SEGURO ELIMINAR"),
            Map.entry("Carpetas de caché",
                    "Datos de aplicaciones que pueden reconstruirse (temp, __pycache__, etc.). SEGURO ELIMINAR"),
            Map.entry("node_modules",
                    "Dependencias descargadas por proyectos Node.js; se regeneran con npm/yarn."),
            Map.entry("Otros",
                    "Archivos que no encajan en las categorías anteriores.")
    );
}
