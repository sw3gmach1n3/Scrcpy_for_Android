package ro.sw3g.scrcpy;

import java.text.Normalizer;
import java.util.*;
import org.json.*;

/** Searchable bilingual help, separate from its Android presentation. */
final class HelpContent {
    static final class Section {
        final String title,body,kind,command,search;
        Section(JSONObject value)throws JSONException{title=value.getString("title");body=value.getString("body");kind=value.getString("kind");command=value.optString("command","");search=normalize(title+"\n"+body+"\n"+command);}
        boolean matches(String query){return query.isEmpty()||search.contains(query);}
    }
    static final class Topic {
        final String id,title,summary,search;
        final List<Section> sections=new ArrayList<>();
        Topic(JSONObject value)throws JSONException{
            id=value.getString("id");title=value.getString("title");summary=value.getString("summary");search=normalize(title+" "+summary);
            JSONArray entries=value.getJSONArray("sections");for(int i=0;i<entries.length();i++)sections.add(new Section(entries.getJSONObject(i)));
        }
        boolean matches(String query){if(query.isEmpty()||search.contains(query))return true;for(Section section:sections)if(section.matches(query))return true;return false;}
    }
    final List<Topic> topics=new ArrayList<>();
    HelpContent(String json)throws JSONException{JSONArray values=new JSONObject(json).getJSONArray("topics");for(int i=0;i<values.length();i++)topics.add(new Topic(values.getJSONObject(i)));}
    static String normalize(String value){return Normalizer.normalize(value,Normalizer.Form.NFD).replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT).trim();}
}
