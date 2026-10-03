package pl.marek.kingdomminions;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

final class Lang {
    private static final Map<String,Properties> catalogs = new HashMap<>();
    static { for(String language:supportedLanguages())catalogs.put(language.toLowerCase(Locale.ROOT),bundled(language)); }
    private Lang() {}
    static List<String> supportedLanguages() {
        try(InputStream stream=Lang.class.getResourceAsStream("/lang/languages.list")) {
            if(stream==null)throw new IllegalStateException("Missing language index");
            return new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines().map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }catch(IOException error){throw new IllegalStateException("Cannot read language index",error);}
    }
    static Properties bundled(String language) {
        Properties result=new Properties();
        try(InputStream stream=Lang.class.getResourceAsStream("/lang/"+language+".properties")) {
            if(stream==null)throw new IllegalStateException("Missing language resource: "+language);
            result.load(new InputStreamReader(stream,StandardCharsets.UTF_8));return result;
        } catch(IOException e){throw new IllegalStateException("Cannot load language "+language,e);}
    }
    static void load(JavaPlugin plugin) {
        catalogs.clear();for(String language:supportedLanguages())catalogs.put(language.toLowerCase(Locale.ROOT),bundled(language));
        Path directory=plugin.getDataFolder().toPath().resolve("lang");
        try {
            Files.createDirectories(directory);
            for(String language:supportedLanguages())if(!Files.exists(directory.resolve(language+".properties")))plugin.saveResource("lang/"+language+".properties",false);
            try(var files=Files.list(directory)) {
                for(Path file:files.filter(p -> p.getFileName().toString().matches("[a-z]{2,3}([_-][a-zA-Z]{2,4})?\\.properties")).toList()) {
                    String language=file.getFileName().toString().replace(".properties","").replace('_','-').toLowerCase(Locale.ROOT);
                    Properties supplied=new Properties();
                    try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){supplied.load(reader);}
                    catch(IllegalArgumentException error){plugin.getLogger().warning("Invalid language file: "+file.getFileName());continue;}
                    Properties merged=new Properties();merged.putAll(catalogs.getOrDefault(language,new Properties()));
                    for(String key:supplied.stringPropertyNames()) {
                        String english=catalogs.get("en").getProperty(key),value=supplied.getProperty(key);
                        if(english!=null && !value.isBlank() && placeholders(english).equals(placeholders(value)))merged.setProperty(key,value);
                        else plugin.getLogger().warning("Ignored invalid translation: "+file.getFileName()+" / "+key);
                    }
                    catalogs.put(language,merged);
                }
            }
        }catch(IOException e){plugin.getLogger().warning("Language files unavailable; using bundled translations: "+e.getMessage());}
    }
    static Set<String> placeholders(String text) {
        Set<String> result=new TreeSet<>();Matcher matcher=Pattern.compile("\\{\\d+}").matcher(text);
        while(matcher.find())result.add(matcher.group());return result;
    }
    static String text(CommandSender sender,String key,Object... args) {
        Locale locale=sender instanceof Player player ? player.locale() : Locale.ENGLISH;
        return text(locale==null?Locale.ENGLISH:locale,key,args);
    }
    static String text(Locale locale,String key,Object... args) {
        String full=locale.toLanguageTag().toLowerCase(Locale.ROOT);
        Properties chosen=catalogs.getOrDefault(full,catalogs.getOrDefault(locale.getLanguage(),catalogs.get("en")));
        String template=chosen.getProperty(key,catalogs.getOrDefault(locale.getLanguage(),catalogs.get("en")).getProperty(key,catalogs.get("en").getProperty(key)));
        if(template==null)throw new IllegalArgumentException("Unknown translation key: "+key);
        Matcher matcher=Pattern.compile("\\{(\\d+)}").matcher(template);StringBuffer result=new StringBuffer();
        while(matcher.find()) {
            int index=Integer.parseInt(matcher.group(1));
            if(index>=args.length)throw new IllegalArgumentException("Missing translation argument: "+key+" / "+index);
            matcher.appendReplacement(result,Matcher.quoteReplacement(String.valueOf(args[index])));
        }
        matcher.appendTail(result);return result.toString();
    }
    static Failure failure(String key,Object... args){return new Failure(key,args);}
    static String message(CommandSender sender,IllegalArgumentException error) {
        return error instanceof Failure failure ? text(sender,failure.key,failure.args) : error.getMessage();
    }
    static final class Failure extends IllegalArgumentException {
        final String key;final Object[] args;
        Failure(String key,Object[] args){super(key);this.key=key;this.args=args.clone();}
    }
}
