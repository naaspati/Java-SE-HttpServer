package sam.server;

import static java.util.stream.Collectors.partitioningBy;
import static java.util.stream.Collectors.toSet;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class Tester implements Predicate<String> {
    private final Predicate<String> predicate;
    
  public Tester(String value) {
        Map<Boolean, Set<String>> map = 
                Optional.ofNullable(value)
                .map(s -> s.trim().isEmpty() ? null : s)
                .map(s -> s.split("\\s*,\\s*"))
                .map(ary -> Stream.of(ary)
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(s -> s.replace(".", "\\.").replace("*", ".+"))
                        .collect(partitioningBy(s -> s.charAt(0) != '!', toSet()))
                        )
                .orElse(new HashMap<>());

        Function<Boolean , Predicate<String>> get = key -> {
            if(map.get(key) == null || map.get(key).isEmpty())
                return null; 

            return map.get(key)
                    .stream()
                    .map(s -> key ? s : s.substring(1))
                    .map(Pattern::compile)
                    .map(pattern -> (Predicate<String>)(s -> pattern.matcher(s).matches()))
                    .reduce(Predicate::or)
                    .get();        
        };

        Predicate<String> add = get.apply(true);
        Predicate<String> remove = get.apply(false);

        predicate =  string -> remove != null && remove.test(string) ? false : add == null ? false : add.test(string);    
    } 

    @Override
    public boolean test(String t) {
        return predicate.test(t);
    }
}
