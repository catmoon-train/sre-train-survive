package io.wifi.habitrainsurvive.help;

import java.io.StringReader;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class HelpMetadataTest {
    @Test void bundledMetadataIsValidAndEveryAliasHasCanonicalDescription() throws Exception {
        var metadata=HelpMetadata.loadDefaults();assertTrue(metadata.rules().size()>100);
        for(String alias:metadata.aliases().keySet()){
            String canonical=metadata.canonicalRoot(alias);
            assertTrue(metadata.rules().keySet().stream().anyMatch(path->path.equals(canonical)||path.startsWith(canonical+" ")),canonical);
        }
        assertTrue(metadata.match("habi help").exact());assertTrue(metadata.match("homes buy").exact());
        assertTrue(metadata.match("shop addcategory").exact());assertTrue(metadata.match("menu edit").exact());
        assertTrue(metadata.match("title").rule().permission().contains("OP"));
        assertNotEquals(metadata.match("title").rule().permission(), metadata.match("titles").rule().permission());
    }
    @Test void duplicateRulesAndAliasCyclesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->HelpMetadata.read(new StringReader("""
                {"aliases":{"a":"b","b":"a"},"commands":[]}
                """)));
        String json="""
                {"aliases":{},"commands":[{"path":"a","category":"general","description":"测试","permission":"所有玩家"}]}
                """;
        assertThrows(IllegalArgumentException.class,()->HelpMetadata.read(new StringReader(json),new StringReader(json)));
    }
}
