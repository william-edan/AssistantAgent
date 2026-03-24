public class InspectStudioDto {
  public static void main(String[] args) throws Exception {
    Class<?> runReq = Class.forName("com.alibaba.cloud.ai.agent.studio.dto.AgentRunRequest");
    System.out.println("AgentRunRequest:");
    for (var f : runReq.getDeclaredFields()) {
      System.out.println(f.getName() + " : " + f.getType().getName());
    }
    Class<?> msg = Class.forName("com.alibaba.cloud.ai.agent.studio.dto.messages.UserMessageDTO");
    System.out.println("UserMessageDTO:");
    for (var f : msg.getDeclaredFields()) {
      System.out.println(f.getName() + " : " + f.getType().getName());
    }
  }
}
