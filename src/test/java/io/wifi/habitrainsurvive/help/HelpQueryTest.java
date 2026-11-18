package io.wifi.habitrainsurvive.help;

import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;

class HelpQueryTest {
    @Test void parsesDefaultPageChineseAndEnglishCategories() {
        assertEquals(new HelpQuery("all",1),HelpQuery.parse(""));
        assertEquals(new HelpQuery("all",2),HelpQuery.parse("2"));
        assertEquals(new HelpQuery("systemshop",2),HelpQuery.parse("商城 2"));
        assertEquals(new HelpQuery("travel",3),HelpQuery.parse("\"传送与家\" 3"));
        assertEquals(new HelpQuery("claim",1),HelpQuery.parse("claim"));
        assertEquals(new HelpQuery("categories",2),HelpQuery.parse("分类 2"));
    }
    @Test void rejectsUnknownMalformedAndOverflowingPages() {
        for(String query:List.of("0","-1","99999999999999999999999","商城 abc","claim 0","unknown","all 2 extra","2 extra","\"bad"))
            assertThrows(IllegalArgumentException.class,()->HelpQuery.parse(query),query);
    }
    @Test void pagesCoverEverythingExactlyOnceWithCorrectNavigation() {
        var input=IntStream.range(0,17).boxed().toList();var output=new ArrayList<Integer>();
        for(int i=1;i<=3;i++){
            var page=HelpPage.of(input,i,6);output.addAll(page.items());assertEquals(3,page.pages());
            assertEquals(i>1?i-1:0,page.previous());assertEquals(i<3?i+1:0,page.next());
        }
        assertEquals(input,output);assertEquals(5,HelpPage.of(input,3,6).items().size());
    }
    @Test void boundariesAndEmptyPagesDoNotWrapOrInventPages() {
        assertEquals(1,HelpPage.of(List.of(),1,6).pages());
        assertTrue(HelpPage.of(List.of(),1,6).items().isEmpty());
        for(int page:new int[]{0,-1,2,Integer.MAX_VALUE})assertThrows(IllegalArgumentException.class,()->HelpPage.of(List.of(1),page,6));
        assertThrows(IllegalArgumentException.class,()->HelpPage.of(List.of(1),1,0));
        assertEquals(1,HelpPage.of(List.of(1,2,3,4,5,6),1,6).pages());
    }
    @Test void categoryIdsAreUniqueAndLocalizedAliasesResolve() {
        assertEquals(HelpMetadata.CATEGORIES.size(),HelpMetadata.CATEGORIES.stream().map(HelpMetadata.Category::id).distinct().count());
        for(var category:HelpMetadata.CATEGORIES)assertEquals(category.id(),HelpMetadata.categoryId(category.name()));
        assertEquals("playershop",HelpMetadata.categoryId("电子商铺"));
        assertNull(HelpMetadata.categoryId("不存在的分类"));
    }
}
