package zanzibar.bazaar;

import jade.core.Agent;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.HashMap;
import java.util.Map;

/**
 * BazaarAgent acts as the Game Master in the Zanzibar Bazaar scenario.
 * It announces market prices/events, collects sales from PlayerAgents,
 * updates the scoreboard, and manages rounds.
 */
public class BazaarAgent extends Agent {

    // Example spice names; you might prefer an enum or constants
    public static final String CLOVE      = "Clove";
    public static final String CINNAMON   = "Cinnamon";
    public static final String NUTMEG     = "Nutmeg";
    public static final String CARDAMOM   = "Cardamom";

    // Current prices for each spice (coins per unit).
    private Map<String, Integer> currentPrices = new HashMap<>();

    // Track total coins per player (simple scoreboard).
    private Map<String, Integer> scoreboard = new HashMap<>();

    // Round management
    private int currentRound = 1;
    private static final int MAX_ROUNDS = 5; // or any other end condition you want

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " starting as Bazaar Agent...");

        // 1. (Optional) Register in the Directory Facilitator (DF)
        registerService("bazaar-master");

        // 2. Initialize default prices (or load from config)
        currentPrices.put(CLOVE,     20);
        currentPrices.put(CINNAMON,  10);
        currentPrices.put(NUTMEG,    15);
        currentPrices.put(CARDAMOM,  5);

        // 3. Add the main behaviour to handle the entire game (rounds, etc.)
        addBehaviour(new RoundManagerBehaviour(this));
    }

    /**
     * Helper method to register a service with the JADE DF (optional but useful).
     */
    private void registerService(String serviceName) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceName);
        sd.setName(getLocalName() + "-" + serviceName);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    /**
     * Main manager that progresses the game through rounds.
     * A simple version uses a SequentialBehaviour to:
     *  1) Announce prices/events
     *  2) Wait or handle negotiations (not shown here)
     *  3) Collect sales from players
     *  4) Update scoreboard and check end condition
     *  5) Repeat until MAX_ROUNDS
     */
    private class RoundManagerBehaviour extends SequentialBehaviour {

        public RoundManagerBehaviour(Agent a) {
            super(a);
        }

        @Override
        public void onStart() {
            System.out.println("\n--- Starting Round " + currentRound + " ---");

            // Add sub-behaviours for this round
            addSubBehaviour(new AnnouncePricesBehaviour());
            // (If you have a Negotiation phase, you could add it here as well)
            addSubBehaviour(new RequestSalesBehaviour());
            addSubBehaviour(new CollectSalesBehaviour());
            addSubBehaviour(new EndRoundBehaviour());
        }

        /**
         * At the end of the sequence, decide whether to continue or stop.
         */
        @Override
        public int onEnd() {
            if (currentRound < MAX_ROUNDS) {
                currentRound++;
                // Reset the SequentialBehaviour so it can run again
                reset();
                // Re-schedule the same RoundManagerBehaviour to run for the next round
                myAgent.addBehaviour(this);
                return 0; // or simply return super.onEnd();
            } else {
                System.out.println("Reached MAX_ROUNDS. Terminating...");
                myAgent.doDelete();
                return super.onEnd(); 
            }
        }
    }

    // ------------------------------------------------
    //  Sub-behaviours for each step of a round
    // ------------------------------------------------

    /**
     * 1) Announce current prices and any special event to all PlayerAgents.
     */
    private class AnnouncePricesBehaviour extends OneShotBehaviour {

        @Override
        public void action() {
            System.out.println(getLocalName() + ": Announcing prices for Round " + currentRound);

            // (Optional) Generate or announce an event for this round
            String eventMsg = generateRandomEvent();  
            
            // For a real solution, you'd do a DF lookup for all "player" agents,
            // or keep a list of known players. For illustration, let's assume
            // scoreboard has player names. If scoreboard is empty the first round,
            // you might skip or do a DF query.
            
            if (scoreboard.isEmpty()) {
                System.out.println("No players registered in scoreboard yet. (You could do a DF lookup here.)");
                return;
            }

            // Broadcast message with the updated prices & event
            ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
            msg.setConversationId("price-announcement");
            msg.setContent(encodePriceEventInfo(currentPrices, eventMsg));

            for (String playerName : scoreboard.keySet()) {
                msg.addReceiver(getAID(playerName));
            }
            send(msg);

            System.out.println("> Sent price announcement: " + msg.getContent());
        }
    }

    /**
     * 2) Request that players submit how many spices they will sell.
     */
    private class RequestSalesBehaviour extends OneShotBehaviour {

        @Override
        public void action() {
            System.out.println(getLocalName() + ": Requesting sales from players...");

            ACLMessage msg = new ACLMessage(ACLMessage.REQUEST);
            msg.setConversationId("sell-request");
            msg.setContent("How many units of each spice will you sell this round?");

            for (String playerName : scoreboard.keySet()) {
                msg.addReceiver(getAID(playerName));
            }
            send(msg);
        }
    }

    /**
     * 3) Collect the sales (blocking or non-blocking).
     *    This can be done in various ways:
     *    - A parallel behaviour that waits for N replies.
     *    - A loop that collects messages until a timeout or until all players responded.
     */
    private class CollectSalesBehaviour extends Behaviour {

        private int repliesReceived = 0;
        private boolean done = false;

        @Override
        public void action() {
            // Listen for "sell-response" messages
            MessageTemplate mt = MessageTemplate.MatchConversationId("sell-response");
            ACLMessage reply = myAgent.receive(mt);

            if (reply != null) {
                // Parse the player's response: e.g. "Clove=2;Cinnamon=0;Nutmeg=1;Cardamom=3"
                Map<String, Integer> soldMap = decodeSellInfo(reply.getContent());

                // Update scoreboard with new coins gained
                int coinsGained = 0;
                for (Map.Entry<String, Integer> e : soldMap.entrySet()) {
                    String spice = e.getKey();
                    int quantity = e.getValue();
                    int price = currentPrices.getOrDefault(spice, 0);
                    coinsGained += price * quantity;
                }

                String playerName = reply.getSender().getLocalName();
                int oldScore = scoreboard.getOrDefault(playerName, 0);
                scoreboard.put(playerName, oldScore + coinsGained);

                repliesReceived++;
                System.out.println(playerName + " sold " + soldMap + " and earned " + coinsGained + " coins.");
            } else {
                block(); // wait for next message
            }

            // If we've received all replies (assuming scoreboard size is # of players),
            // we can mark done. Otherwise we keep waiting.
            if (repliesReceived >= scoreboard.size()) {
                done = true;
            }
        }

        @Override
        public boolean done() {
            return done;
        }
    }

    /**
     * 4) EndRoundBehaviour:
     *    Possibly update or adjust prices, print scoreboard,
     *    then let RoundManagerBehaviour decide if we continue or end.
     */
    private class EndRoundBehaviour extends OneShotBehaviour {

        @Override
        public void action() {
            System.out.println("\n--- End of Round " + currentRound + " ---");
            System.out.println("Current Scoreboard:");
            for (Map.Entry<String, Integer> entry : scoreboard.entrySet()) {
                System.out.println("  " + entry.getKey() + " has " + entry.getValue() + " coins.");
            }

            // Adjust next round's prices or incorporate event logic
            adjustPricesForNextRound();
        }
    }

    // ------------------------------------------------
    //  Utility / Helper Methods
    // ------------------------------------------------

    /**
     * Example method to generate a random event message (placeholder).
     */
    private String generateRandomEvent() {
        double r = Math.random();
        if (r < 0.33) {
            return "Storm in Indian Ocean: Next round's clove price +5";
        } else if (r < 0.66) {
            return "Sultan's tax on sales: Next round all sales -10% profit (not implemented yet)";
        } else {
            return "No special event this round";
        }
    }

    /**
     * Example: encode market prices and event into a single string.
     * In a real system, use JSON or another structured format if preferred.
     */
    private String encodePriceEventInfo(Map<String, Integer> prices, String eventMsg) {
        // E.g., "Clove=20;Cinnamon=10;Nutmeg=15;Cardamom=5|EVENT:Storm..."
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : prices.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(";");
        }
        sb.append("|EVENT:").append(eventMsg);
        return sb.toString();
    }

    /**
     * Decode a player's sell info, e.g. "Clove=2;Cinnamon=1;Nutmeg=0..."
     */
    private Map<String, Integer> decodeSellInfo(String content) {
        Map<String, Integer> soldMap = new HashMap<>();
        try {
            String[] parts = content.split(";");
            for (String p : parts) {
                String[] kv = p.split("=");
                if (kv.length == 2) {
                    soldMap.put(kv[0], Integer.parseInt(kv[1]));
                }
            }
        } catch (Exception e) {
            System.err.println("Error parsing sell info: " + content);
        }
        return soldMap;
    }

    /**
     * Adjust prices for the next round (dummy logic for illustration).
     */
    private void adjustPricesForNextRound() {
        for (String spice : currentPrices.keySet()) {
            int oldPrice = currentPrices.get(spice);
            // e.g., +/- up to 2 coins randomly
            int newPrice = oldPrice + (int)((Math.random() - 0.5) * 4);
            if (newPrice < 1) newPrice = 1;
            currentPrices.put(spice, newPrice);
        }
        System.out.println("Adjusted prices for next round: " + currentPrices);
    }

    @Override
    protected void takeDown() {
        // Deregister from the DF
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println(getLocalName() + " terminating.");
    }
}
