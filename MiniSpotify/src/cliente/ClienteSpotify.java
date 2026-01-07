package cliente; // Coincide con tu otra carpeta

import java.io.*;
import java.net.*;
import java.util.Scanner;

public class ClienteSpotify {
    public static void main(String[] args) {
        Scanner sc = new Scanner(System.in);
        System.out.println("--- MINI SPOTIFY CLIENTE ---");
        System.out.print("Nombre de la cancion : ");
        String cancion = sc.nextLine();

        // Siempre le pedimos al puerto 8000 (el balanceador)
        solicitarAlServidor("http://localhost:8000/" + cancion);
    }

    public static void solicitarAlServidor(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();

            // IMPORTANTE: Permitir que siga la redirección al 8081 u 8082
            conn.setInstanceFollowRedirects(true);

            System.out.println("Buscando en sistema...");
            int status = conn.getResponseCode();

            if (status == HttpURLConnection.HTTP_OK) {
                // El URL final puede haber cambiado debido a la redirección
                System.out.println("¡Conectado exitosamente al servidor final!");
                System.out.println("Descargando desde: " + conn.getURL());

                String nombreArchivo = "reproduciendo_" + cancionDesdeUrl(conn.getURL().toString());

                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(nombreArchivo)) {

                    byte[] buffer = new byte[4096];
                    int leido;
                    while ((leido = in.read(buffer)) != -1) {
                        out.write(buffer, 0, leido);
                    }
                }
                System.out.println("Archivo '" + nombreArchivo + "' recibido y listo para sonar.");
            } else {
                System.out.println("Error: No se encontró la canción (Status " + status + ")");
            }

        } catch (Exception e) {
            System.err.println("Error de conexión: " + e.getMessage());
        }
    }

    private static String cancionDesdeUrl(String url) {
        return url.substring(url.lastIndexOf('/') + 1);
    }
}