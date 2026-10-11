import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.Map;
import java.util.TreeMap;

/** Smoke test: proves Gson 2.14 still (de)serializes plain static nested POJOs without reflection flags. */
public class GsonProbe {
    public static class Root {
        public int version = 1;
        public Map<String, Player> players = new TreeMap<>();
    }

    public static class Player {
        public String name;
        public String preset;
        public Map<String, String> granted = new TreeMap<>();
    }

    public static void main(String[] args) {
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        Root root = new Root();
        Player p = new Player();
        p.name = "Alice";
        p.preset = "custom";
        p.granted.put("tp", "GAMEMASTERS");
        root.players.put("old-root-uuid", p);

        StringBuilder sb = new StringBuilder();
        gson.toJson(root, sb);
        System.out.println(sb);

        Root back = gson.fromJson(sb.toString(), Root.class);
        System.out.println("roundtrip=" + back.players.get("old-root-uuid").granted);
    }
}
