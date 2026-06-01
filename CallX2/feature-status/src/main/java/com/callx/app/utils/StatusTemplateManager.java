package com.callx.app.utils;

import java.util.*;

/**
 * StatusTemplateManager v26 — Pre-designed status templates.
 * User selects template → only fills text; background/font/colors preset.
 */
public final class StatusTemplateManager {
    private StatusTemplateManager() {}

    public static class Template {
        public String id, name, category;
        public String bgColor, bgColor2;       // solid or gradient
        public List<String> gradientColors;
        public String textColor, fontStyle;
        public String textAlign;
        public float  textSize;
        public String emojiDecor;              // decorative emoji shown around text
        public String previewEmoji;            // for template picker grid

        public Template(String id, String name, String cat, String bg, String bg2,
                        String tc, String fs, String align, float sz, String emoji, String prev) {
            this.id=id; this.name=name; this.category=cat; this.bgColor=bg; this.bgColor2=bg2;
            this.textColor=tc; this.fontStyle=fs; this.textAlign=align; this.textSize=sz;
            this.emojiDecor=emoji; this.previewEmoji=prev;
        }
    }

    public static List<Template> getAllTemplates() {
        List<Template> t = new ArrayList<>();
        // Gradient templates
        t.add(new Template("grad_purple","Purple Vibe","Gradient","#6200EE","#03DAC5","#FFFFFF","bold","center",28f,"✨","💜"));
        t.add(new Template("grad_sunset","Sunset","Gradient","#FF6F00","#E53935","#FFFFFF","bold","center",26f,"🌅","🌇"));
        t.add(new Template("grad_ocean","Ocean Blue","Gradient","#0288D1","#00BCD4","#FFFFFF","italic","center",26f,"🌊","🌊"));
        t.add(new Template("grad_forest","Forest","Gradient","#388E3C","#AED581","#FFFFFF","handwriting","center",24f,"🌿","🌿"));
        t.add(new Template("grad_rose","Rose Gold","Gradient","#E91E63","#FF9800","#FFFFFF","bold","center",26f,"🌹","🌸"));
        t.add(new Template("grad_midnight","Midnight","Gradient","#1A237E","#311B92","#FFFFFF","bold","center",28f,"🌙","🌌"));
        // Solid templates
        t.add(new Template("solid_black","Minimal Black","Minimal","#000000",null,"#FFFFFF","condensed","center",32f,"","⬛"));
        t.add(new Template("solid_white","Clean White","Minimal","#FFFFFF",null,"#000000","default","center",28f,"","⬜"));
        t.add(new Template("solid_red","Bold Red","Bold","#B71C1C",null,"#FFFFFF","bold","center",30f,"🔥","🔴"));
        t.add(new Template("solid_teal","Teal Fresh","Fresh","#00695C",null,"#FFFFFF","default","left",26f,"","🟢"));
        // Handwriting templates
        t.add(new Template("hand_warm","Warm Handwriting","Handwriting","#FFF8E1",null,"#5D4037","handwriting","center",30f,"✍️","📝"));
        t.add(new Template("hand_cool","Cool Handwriting","Handwriting","#E3F2FD",null,"#1565C0","handwriting","center",30f,"✍️","🖊️"));
        // Celebration templates
        t.add(new Template("celebrate_bday","Birthday","Celebration","#F06292","#AB47BC","#FFFFFF","bold","center",28f,"🎉🎂🎊","🎂"));
        t.add(new Template("celebrate_new","New Year","Celebration","#212121","#37474F","#FFD700","bold","center",30f,"🎆✨🎇","🎆"));
        t.add(new Template("celebrate_wed","Wedding","Celebration","#FAFAFA",null,"#880E4F","serif","center",26f,"💍💕","💒"));
        // Motivational
        t.add(new Template("moti_1","Rise & Grind","Motivation","#263238",null,"#ECEFF1","condensed","center",28f,"💪","💪"));
        t.add(new Template("moti_2","Good Vibes","Motivation","#F3E5F5","#E8EAF6","#6A1B9A","italic","center",24f,"🌈","🌈"));
        return t;
    }

    public static List<String> getCategories() {
        return Arrays.asList("All","Gradient","Minimal","Bold","Handwriting","Celebration","Motivation");
    }

    public static List<Template> getByCategory(String category) {
        if ("All".equals(category)) return getAllTemplates();
        List<Template> result = new ArrayList<>();
        for (Template t : getAllTemplates()) if (category.equals(t.category)) result.add(t);
        return result;
    }
}
