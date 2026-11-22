import com.google.ai.edge.litertlm.*;
import com.google.gson.Gson;
import java.util.*;
public class ModelSmoke {
  public static void main(String[] args) throws Exception {
    Engine.Companion.setNativeMinLogSeverity(LogSeverity.ERROR);
    long started=System.nanoTime();
    try (Engine engine = new Engine(new EngineConfig(args[0], new Backend.CPU(2,null), null, null, 2048, null, "/workspace/work/litert-research/cache"))) {
      engine.initialize();
      System.out.println("Loaded in "+((System.nanoTime()-started)/1000000)+" ms");
      var config = new ConversationConfig(null,List.of(),List.of(),new SamplerConfig(1,1.0,0.0,0),true,null,Map.of("enable_thinking",false),null,false,384,null,false);
      for (String text: List.of("Do not open the door.","Jason, where are you?","I told you we shouldn't have come here. Now we have to find another way out.","Don't shoot! He's on our side.")) {
        long t=System.nanoTime();
        try (Conversation c=engine.createConversation(config)) {
          String prompt="Translate from English to Czech. Reply only with the translation.\n"+text+"\n/no_think";
          var msg=c.sendMessage(prompt);
          StringBuilder out=new StringBuilder();
          for(Content part:msg.getContents().getContents()) if(part instanceof Content.Text tt) out.append(tt.getText());
          System.out.println(new Gson().toJson(Map.of("source",text,"output",out.toString(),"elapsed_ms",(System.nanoTime()-t)/1000000)));
        }
      }
    }
  }
}
