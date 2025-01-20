package zanzibar.bazaar;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;

import java.util.*;

/**
 * A more advanced PlayerAgent that:
 * 1. Registers as "bazaar-player".
 * 2. Listens for GM updates (price announcements, sell requests).
 * 3. Periodically initiates trade proposals to other players.
 * 4. Responds to trade proposals from others.
 */
public class PlayerAgent extends Agent {

    // -----------------------------------
    // Fields / State
    // -----------------------------------
    private Map<String, Integer> inventory = new HashMap<>();
    private int coins = 50; // Starting coins
    private Map<String, Integer> currentPrices = new HashMap<>();
    private String currentEvent = "No event";

    // We keep a dynamic list of other known players for negotiation
    // (discovered via the DF or from the GM).
    private Set<AID> otherPlayers = new HashSet<>();

    // For strategy/tracking. E.g. how often we propose trades, etc.
    private static final long NEGOTIATION_INTERVAL = 5000; // 5 seconds, or triggered once per round

    @Override
    protected void setup() {
        System.out.println(getLocalName() + " is starting. Registering as bazaar-player...");

        // 1) Register as player
        registerAsPlayer("bazaar-player");

        // 2) Initialize some default inventory
        inventory.put(BazaarAgent.CLOVE,     3);
        inventory.put(BazaarAgent.CINNAMON,  5);
        inventory.put(BazaarAgent.NUTMEG,    2);
        inventory.put(BazaarAgent.CARDAMOM,  4);

        // 3) Discover other players (optional: we can do it here or periodically)
        discoverOtherPlayers();

        // 4) Add Behaviours
        addBehaviour(new MarketMessageListener());        // Handle GM messages
        addBehaviour(new NegotiationResponder());         // Handle incoming trade proposals
        addBehaviour(new NegotiationInitiatorBehavior(this, NEGOTIATION_INTERVAL)); 
    }

    @Override
    protected void takeDown() {
        // Deregister from DF
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
        System.out.println(getLocalName() + " is terminating.");
    }

    // ---------------------------------------------------------------------
    //  DF Registration & Discovery
    // ---------------------------------------------------------------------
    private void registerAsPlayer(String serviceType) {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(serviceType);
        sd.setName(getLocalName() + "-" + serviceType);
        dfd.addServices(sd);

        try {
            DFService.register(this, dfd);
        } catch (FIPAException e) {
            e.printStackTrace();
        }
    }

    /**
     * Looks up all agents of type "bazaar-player" in the DF
     * and stores them in otherPlayers (except itself).
     */
    private void discoverOtherPlayers() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("bazaar-player");
        template.addServices(sd);

        try {
            DFAgentDescription[] results = DFService.search(this, template);
            for (DFAgentDescription dfad : results) {
                AID provider = dfad.getName();
                if (!provider.equals(getAID())) {
                    otherPlayers.add(provider);
                }
            }
            System.out.println(getLocalName() + " discovered " + otherPlayers.size() + " other player(s).");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }
    }

    // ---------------------------------------------------------------------
    //  Behaviours
    // ---------------------------------------------------------------------

    /**
     * Listens for:
     *  - "price-announcement" (INFORM) from BazaarAgent
     *  - "sell-request"       (REQUEST) from BazaarAgent
     *  - (We handle trade proposals in a separate NegotiationResponder)
     */
    private class MarketMessageListener extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage msg = myAgent.receive();
            if (msg != null) {
                String convoId = msg.getConversationId();
                if ("price-announcement".equals(convoId)) {
                    handlePriceAnnouncement(msg);
                }
                else if ("sell-request".equals(convoId)) {
                    handleSellRequest(msg);
                }
                else {
                    // Not for this behaviour
                    block();
                }
            } else {
                block();
            }
        }
    }

    /**
     * Responds to trade offers from other PlayerAgents.
     * (Listens for "trade-offer" conversationId with a PROPOSE performative.)
     */
    private class NegotiationResponder extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchConversationId("trade-offer");
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    // handle the proposed exchange
                    ACLMessage reply = msg.createReply();
                    Map<String, Integer> offered = new HashMap<>();
                    Map<String, Integer> requested = new HashMap<>();

                    // Example content: "OFFER:Cardamom=2; -> REQUEST:Clove=1;Cinnamon=1"
                    parseTradeProposal(msg.getContent(), offered, requested);

                    if (isTradeAcceptable(offered, requested)) {
                        // Accept
                        reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                        reply.setContent("Trade accepted!");
                        // Update my inventory to reflect the exchange
                        executeTrade(offered, requested);
                        System.out.println(getLocalName() + " accepted trade with " + msg.getSender().getLocalName()
                                + ": " + msg.getContent());
                    } else {
                        // Reject
                        reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
                        reply.setContent("Trade rejected: not beneficial or not possible.");
                        System.out.println(getLocalName() + " rejected trade from " + msg.getSender().getLocalName()
                                + ": " + msg.getContent());
                    }
                    send(reply);
                }
            } else {
                block();
            }
        }
    }

    /**
     * Periodically initiates trade offers to other players.
     * This example uses a TickerBehaviour, but you could trigger it
     * once per round or based on an event.
     */
    private class NegotiationInitiatorBehavior extends TickerBehaviour {

        public NegotiationInitiatorBehavior(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            // Attempt a single trade proposal to a random other player (if any)
            if (!otherPlayers.isEmpty() && !inventory.isEmpty()) {
                AID partner = pickRandomPlayer();
                // Construct a proposal. For example, "I'll give you 2 Cardamom if you give me 1 Clove + 1 Cinnamon"
                Map<String, Integer> offered = new HashMap<>();
                Map<String, Integer> requested = new HashMap<>();

                // Simple example strategy: check if there's a spice we have a "surplus" of
                // and a spice we want to get more of (based on price or personal preference).
                decideWhatToOfferAndRequest(offered, requested);

                if (!offered.isEmpty() && !requested.isEmpty()) {
                    String content = encodeTradeProposal(offered, requested);

                    ACLMessage proposeMsg = new ACLMessage(ACLMessage.PROPOSE);
                    proposeMsg.setConversationId("trade-offer");
                    proposeMsg.addReceiver(partner);
                    proposeMsg.setContent(content);
                    send(proposeMsg);

                    System.out.println(getLocalName() + " -> " + partner.getLocalName()
                            + ": PROPOSE " + content);
                }
            }
        }

        private AID pickRandomPlayer() {
            int index = new Random().nextInt(otherPlayers.size());
            return new ArrayList<>(otherPlayers).get(index);
        }
    }

    // ---------------------------------------------------------------------
    //  Handlers for GM messages (price announcement & sell request)
    // ---------------------------------------------------------------------

    private void handlePriceAnnouncement(ACLMessage msg) {
        System.out.println(getLocalName() + " received price-announcement:\n  " + msg.getContent());
        parsePriceEventInfo(msg.getContent());
        // Possibly adapt strategy or store the event
    }

    private void handleSellRequest(ACLMessage msg) {
        System.out.println(getLocalName() + " received sell-request from " + msg.getSender().getLocalName());

        // Decide how many units to sell after negotiations. 
        // Could be more strategic if we anticipate future price changes.
        Map<String, Integer> sellDecision = decideSales(currentPrices, inventory);

        // Build a string like "Clove=2;Cinnamon=1;Nutmeg=0;Cardamom=3"
        String sellContent = encodeSellInfo(sellDecision);

        ACLMessage reply = msg.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setConversationId("sell-response");
        reply.setContent(sellContent);
        send(reply);

        System.out.println(getLocalName() + " -> sell-response: " + sellContent);

        // Decrement local inventory
        updateLocalInventory(sellDecision);
    }

    // ---------------------------------------------------------------------
    //  Negotiation: Propose/Accept/Reject
    // ---------------------------------------------------------------------

    /**
     * Decide which spices to offer vs. request in a trade.
     * This example is simple: we might pick a spice that we have "too many" of,
     * and request a spice that has a higher price or is "rare" in our inventory.
     */
    private void decideWhatToOfferAndRequest(Map<String, Integer> offered, Map<String, Integer> requested) {
        // Example: find the spice with the highest quantity in our inventory
        // and offer 1 or 2 units of it. Then pick a spice with a high price we have little of
        // to request in return.
        String maxSpice = null; 
        int maxQty = 0;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getValue() > maxQty) {
                maxQty = e.getValue();
                maxSpice = e.getKey();
            }
        }

        // Find the spice with the highest price that we have the least of
        String highPriceSpice = null;
        int highestPrice = 0;
        for (String spice : currentPrices.keySet()) {
            int price = currentPrices.get(spice);
            if (price > highestPrice) {
                highestPrice = price;
                highPriceSpice = spice;
            }
        }

        if (maxSpice != null && highPriceSpice != null && !maxSpice.equals(highPriceSpice)) {
            // Offer 2 units of maxSpice if we have at least 2
            int toOffer = Math.min(2, inventory.get(maxSpice));
            if (toOffer > 0) {
                offered.put(maxSpice, toOffer);
                // Request 1 or 2 units of highPriceSpice
                requested.put(highPriceSpice, 1); 
            }
        }
    }

    /**
     * Decide if a trade is acceptable. Very basic logic:
     * - Check if we have enough of the offered items.
     * - Check if the requested items do not exceed our current inventory? (Though we *gain* these)
     * - Check if the "value" of what we get is >= the "value" of what we give (based on currentPrices).
     */
    private boolean isTradeAcceptable(Map<String, Integer> offered, Map<String, Integer> requested) {
        // 1. Do we have enough inventory of the items they're asking for? 
        //    (i.e. the "requested" from our perspective means we have to "give away" those spices).
        //    CAREFUL: the perspective of "offered" vs "requested" might be reversed 
        //    depending on how we parse it. 
        //    We'll assume "offered" is from the proposer to us, "requested" is what they want from us.
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qtyNeeded = req.getValue();
            int currentQty = inventory.getOrDefault(spice, 0);
            if (qtyNeeded > currentQty) {
                return false; // We don't have enough to give
            }
        }

        // 2. Compare approximate "value" 
        int valueOffered = 0;
        for (Map.Entry<String, Integer> off : offered.entrySet()) {
            String spice = off.getKey();
            int qty = off.getValue();
            int price = currentPrices.getOrDefault(spice, 0);
            valueOffered += qty * price;
        }

        int valueRequested = 0;
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qty = req.getValue();
            int price = currentPrices.getOrDefault(spice, 0);
            valueRequested += qty * price;
        }

        // If we get at least as much "value" as we give, accept (basic example).
        return (valueOffered >= valueRequested);
    }

    /**
     * Executes the trade: we gain the 'offered' items, and lose the 'requested' items.
     */
    private void executeTrade(Map<String, Integer> offered, Map<String, Integer> requested) {
        // We add what's offered to our inventory
        for (Map.Entry<String, Integer> off : offered.entrySet()) {
            String spice = off.getKey();
            int qty = off.getValue();
            int currentQty = inventory.getOrDefault(spice, 0);
            inventory.put(spice, currentQty + qty);
        }

        // We remove what's requested from our inventory
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qty = req.getValue();
            int currentQty = inventory.getOrDefault(spice, 0);
            int newQty = Math.max(0, currentQty - qty);
            inventory.put(spice, newQty);
        }
    }

    // ---------------------------------------------------------------------
    //  Utility: Encode / Decode Trade Proposals
    // ---------------------------------------------------------------------

    /**
     * Encode a proposal: "OFFER:spiceA=2;spiceB=1 -> REQUEST:spiceC=1;spiceD=2"
     */
    private String encodeTradeProposal(Map<String, Integer> offered, Map<String, Integer> requested) {
        StringBuilder sb = new StringBuilder();
        sb.append("OFFER:");
        boolean first = true;
        for (Map.Entry<String, Integer> e : offered.entrySet()) {
            if (!first) sb.append(";");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        sb.append(" -> REQUEST:");
        first = true;
        for (Map.Entry<String, Integer> e : requested.entrySet()) {
            if (!first) sb.append(";");
            sb.append(e.getKey()).append("=").append(e.getValue());
            first = false;
        }
        return sb.toString();
    }

    /**
     * Parse a proposal into two maps: offered and requested.
     * Example content: "OFFER:Cardamom=2; -> REQUEST:Clove=1;Cinnamon=1"
     */
    private void parseTradeProposal(String content, Map<String, Integer> offered, Map<String, Integer> requested) {
        // Split by " -> "
        // Then parse the substring after "OFFER:" and the substring after "REQUEST:"
        try {
            String[] parts = content.split("->");
            if (parts.length == 2) {
                // OFFER part
                String offerPart = parts[0].trim(); // e.g. "OFFER:Cardamom=2;Cinnamon=1"
                offerPart = offerPart.replace("OFFER:", "").trim();
                String[] offers = offerPart.split(";");
                for (String o : offers) {
                    String[] kv = o.split("=");
                    if (kv.length == 2) {
                        offered.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                    }
                }
                // REQUEST part
                String requestPart = parts[1].trim(); // e.g. "REQUEST:Clove=1;Cinnamon=1"
                requestPart = requestPart.replace("REQUEST:", "").trim();
                String[] requests = requestPart.split(";");
                for (String r : requests) {
                    String[] kv = r.split("=");
                    if (kv.length == 2) {
                        requested.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(getLocalName() + ": Error parsing trade proposal: " + content);
        }
    }

    // ---------------------------------------------------------------------
    //  Selling Logic (post-negotiation)
    // ---------------------------------------------------------------------

    /**
     * Example strategy for deciding how many items to sell:
     * - If price >= 10, sell half the inventory
     * - If an event warns of a higher future price, might keep more
     */
    private Map<String, Integer> decideSales(Map<String, Integer> prices, Map<String, Integer> myInventory) {
        Map<String, Integer> decision = new HashMap<>();
        for (String spice : myInventory.keySet()) {
            int quantity = myInventory.get(spice);
            int price = prices.getOrDefault(spice, 0);

            if (price >= 10) {
                // Sell half
                int toSell = quantity / 2;
                decision.put(spice, toSell);
            } else {
                // Sell none
                decision.put(spice, 0);
            }
        }
        return decision;
    }

    /**
     * Reduce local inventory after we "commit" to selling to the Bazaar
     */
    private void updateLocalInventory(Map<String, Integer> sellDecision) {
        for (Map.Entry<String, Integer> entry : sellDecision.entrySet()) {
            String spice = entry.getKey();
            int sellQty = entry.getValue();
            int currentQty = inventory.getOrDefault(spice, 0);
            int newQty = Math.max(0, currentQty - sellQty);
            inventory.put(spice, newQty);
        }
    }

    // ---------------------------------------------------------------------
    //  Price/Event Parsing
    // ---------------------------------------------------------------------

    /**
     * Parse "Clove=20;Cinnamon=10;Nutmeg=15;Cardamom=5|EVENT:Storm..."
     */
    private void parsePriceEventInfo(String content) {
        String[] mainParts = content.split("\\|EVENT:");
        if (mainParts.length > 0) {
            String pricePart = mainParts[0];
            for (String spicePair : pricePart.split(";")) {
                String[] kv = spicePair.split("=");
                if (kv.length == 2) {
                    String spiceName = kv[0].trim();
                    int price = Integer.parseInt(kv[1].trim());
                    currentPrices.put(spiceName, price);
                }
            }
        }
        if (mainParts.length > 1) {
            currentEvent = mainParts[1];
        }
    }

    /**
     * Encode a map of sales like {Clove=2, Cinnamon=1} -> "Clove=2;Cinnamon=1"
     */
    private String encodeSellInfo(Map<String, Integer> sellMap) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sellMap.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(";");
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1); // remove trailing semicolon
        }
        return sb.toString();
    }
}
