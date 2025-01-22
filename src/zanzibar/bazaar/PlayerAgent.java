package zanzibar.bazaar;

import jade.core.Agent;
import jade.core.AID;
import jade.core.behaviours.*;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;

import java.util.*;

/**
 * A PlayerAgent that:
 *  - Registers as "bazaar-player" in the DF.
 *  - Waits for "round-start" from the GM, sets a negotiationOpen flag to true.
 *  - Has a NegotiationInitiatorBehavior that checks negotiationOpen each tick
 *    and attempts trades up to a maximum number of attempts (fallback).
 *  - Responds to trade/alliance proposals from other players.
 *  - When the GM requests a sell decision, the player replies only after 
 *    at least one negotiation has succeeded or the fallback is triggered.
 */
public class PlayerAgent extends Agent {
    // ------------------------------------------------
    // Fields / State
    // ------------------------------------------------
    private Map<String, Integer> inventory = new HashMap<>();
    private int coins = 50; // Starting coins
    private Map<String, Integer> currentPrices = new HashMap<>();
    private String currentEvent = "No event";

    // List of known players (besides me)
    private Set<AID> otherPlayers = new HashSet<>();

    // Keep track of alliances or rivalries
    private Set<AID> allies = new HashSet<>();
    private Set<AID> rivals = new HashSet<>();

    // Negotiation phase control
    private boolean negotiationOpen = false;
    private int negotiationAttempts = 0;
    private static final int MAX_NEGOTIATION_ATTEMPTS = 3;

    // For demonstration: how often we spontaneously propose trades
    private static final long NEGOTIATION_INTERVAL = 4000; // e.g., every 4 seconds

    // Sell-request handling
    private ACLMessage pendingSellRequest = null;

    // Constructor-like setup
    @Override
    protected void setup() {
        System.out.println(getLocalName() + " is starting. Registering as bazaar-player...");

        // 1) Register as player
        registerAsPlayer("zanzibar-player");

        // 2) Initialize default inventory
        inventory.put(BazaarAgent.CLOVE,    3);
        inventory.put(BazaarAgent.CINNAMON, 5);
        inventory.put(BazaarAgent.NUTMEG,   2);
        inventory.put(BazaarAgent.CARDAMOM, 4);

        // 3) Add Behaviors:
        //    - A CyclicBehaviour that listens for messages from the BazaarAgent
        addBehaviour(new MarketMessageListener());

        //    - Responders for trade/alliance
        addBehaviour(new NegotiationResponder());
        addBehaviour(new AllianceResponder());

        //    - A sabotage (optional) ticker
        addBehaviour(new SabotageBehavior(this, 15000));

        //    - A negotiation initiator that always runs, but is guarded by negotiationOpen
        addBehaviour(new NegotiationInitiatorBehavior(this, NEGOTIATION_INTERVAL));
    }

    @Override
    protected void takeDown() {
        // Deregister from the DF
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

    private void discoverOtherPlayers() {
        DFAgentDescription template = new DFAgentDescription();
        ServiceDescription sd = new ServiceDescription();
        sd.setType("zanzibar-player");
        template.addServices(sd);

        try {
            DFAgentDescription[] results = DFService.search(this, template);
            otherPlayers.clear();
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
    //  Behaviors
    // ---------------------------------------------------------------------

    /**
     * Listens for GM messages:
     *  - "round-start"  => set negotiationOpen = true
     *  - "price-announcement" => parse new prices
     *  - "sell-request" => handle the request, but only respond after negotiation is done
     */
    private class MarketMessageListener extends CyclicBehaviour {
        @Override
        public void action() {
            ACLMessage msg = myAgent.receive();
            if (msg != null) {
                String convoId = msg.getConversationId();
                switch (convoId) {
                    case "round-start":
                        System.out.println(getLocalName()
                                + " received 'round-start'. Re-discovering players and enabling negotiation...");
                        discoverOtherPlayers();

                        // Reset the negotiation state for the new round
                        negotiationAttempts = 0;
                        break;

                    case "price-announcement":
                        handlePriceAnnouncement(msg);
                        negotiationOpen = true;  // allow negotiation attempts
                        break;

                    case "sell-request":
                        handleSellRequest(msg);
                        break;
                        
                    case "game-over":
                    	handleGameOver(msg);
                    	break;

                    default:
                        block();
                }
            } else {
                block();
            }
        }
    }

    /**
     * Periodically attempts to initiate a trade proposal with a random other player,
     * but only if negotiationOpen = true.
     */
    private class NegotiationInitiatorBehavior extends TickerBehaviour {
        public NegotiationInitiatorBehavior(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            // If negotiation is not open, skip
            if (!negotiationOpen) {
                return;
            }

            negotiationAttempts++;

            // If we've reached the max attempts with no success, fallback
            if (negotiationAttempts > MAX_NEGOTIATION_ATTEMPTS) {
                System.out.println(getLocalName() + " reached max negotiation attempts without success. "
                        + "Fallback: ending negotiation phase and forcing sell if pending.");
                negotiationOpen = false;
                // If a sell request is pending, respond now
                if (pendingSellRequest != null) {
                    sendSellResponse();
                }
                return;
            }

            // Attempt a trade if we have other players and some inventory
            if (!otherPlayers.isEmpty() && !inventory.isEmpty()) {
                AID partner = pickRandomPlayer();
                // Construct a proposal
                Map<String, Integer> offered = new HashMap<>();
                Map<String, Integer> requested = new HashMap<>();
                decideWhatToOfferAndRequest(offered, requested, partner);

                if (!offered.isEmpty() && !requested.isEmpty()) {
                    String content = encodeTradeProposal(offered, requested);

                    ACLMessage proposeMsg = new ACLMessage(ACLMessage.PROPOSE);
                    proposeMsg.setConversationId("trade-offer");
                    proposeMsg.addReceiver(partner);
                    proposeMsg.setContent(content);
                    send(proposeMsg);

                    System.out.println(getLocalName() + " -> " + partner.getLocalName()
                            + ": PROPOSE " + content);
                } else {
                    System.out.println(getLocalName() + ": No valid trade offer to propose at this time.");
                }
            } else {
                System.out.println(getLocalName() + ": Negotiation not possible (no players or no inventory).");
            }
        }

        private AID pickRandomPlayer() {
            List<AID> list = new ArrayList<>(otherPlayers);
            return list.get(new Random().nextInt(list.size()));
        }
    }

    /**
     * Respond to *trade offers* from other PlayerAgents (conversationId "trade-offer").
     */
    private class NegotiationResponder extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchConversationId("trade-offer");
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    ACLMessage reply = msg.createReply();
                    Map<String, Integer> offered = new HashMap<>();
                    Map<String, Integer> requested = new HashMap<>();

                    decodeTradeProposal(msg.getContent(), offered, requested);

                    // Check if the trade is acceptable to us
                    if (isTradeAcceptable(offered, requested, msg.getSender())) {
                        // Accept
                        reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                        reply.setContent("Trade accepted!");
                        executeTrade(offered, requested);

                        // Mark that we had a successful negotiation => end negotiation phase
                        System.out.println(getLocalName() + " accepted trade with " 
                                + msg.getSender().getLocalName() + ": " + msg.getContent());
                        onNegotiationSuccess();
                    } else {
                        // Reject
                        reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
                        reply.setContent("Trade rejected: not beneficial or not possible.");
                        System.out.println(getLocalName() + " rejected trade from "
                                + msg.getSender().getLocalName() + ": " + msg.getContent());
                    }
                    send(reply);
                }
            } else {
                block();
            }
        }
    }

    /**
     * Respond to *alliance or pact proposals* (conversationId "alliance-offer").
     * Basic logic: accept if not a rival.
     */
    private class AllianceResponder extends CyclicBehaviour {
        @Override
        public void action() {
            MessageTemplate mt = MessageTemplate.MatchConversationId("alliance-offer");
            ACLMessage msg = myAgent.receive(mt);
            if (msg != null) {
                if (msg.getPerformative() == ACLMessage.PROPOSE) {
                    ACLMessage reply = msg.createReply();
                    if (!rivals.contains(msg.getSender())) {
                        reply.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                        reply.setContent("Alliance accepted!");
                        allies.add(msg.getSender());
                        // If you want forming an alliance to count as "negotiation success", 
                        // you can call onNegotiationSuccess() here too.
                        System.out.println(getLocalName() + " formed an alliance with "
                                + msg.getSender().getLocalName());
                    } else {
                        reply.setPerformative(ACLMessage.REJECT_PROPOSAL);
                        reply.setContent("Alliance rejected. You are a known rival.");
                        System.out.println(getLocalName() + " rejected alliance from "
                                + msg.getSender().getLocalName());
                    }
                    send(reply);
                }
            } else {
                block();
            }
        }
    }

    /**
     * Optional sabotage behavior that tries to mislead a rival every 15s.
     */
    private class SabotageBehavior extends TickerBehaviour {
        public SabotageBehavior(Agent a, long period) {
            super(a, period);
        }

        @Override
        protected void onTick() {
            if (!rivals.isEmpty()) {
                AID target = pickRandomRival();
                ACLMessage deceptionMsg = new ACLMessage(ACLMessage.INFORM);
                deceptionMsg.setConversationId("rumor");
                deceptionMsg.addReceiver(target);
                deceptionMsg.setContent("Rumor: Next round, Clove price will collapse to 1 coin!");
                send(deceptionMsg);
                System.out.println(getLocalName() + " attempts sabotage (false rumor) on " 
                                   + target.getLocalName());
            }
        }

        private AID pickRandomRival() {
            List<AID> rList = new ArrayList<>(rivals);
            return rList.get(new Random().nextInt(rList.size()));
        }
    }

    // ---------------------------------------------------------------------
    //  Handlers for GM messages
    // ---------------------------------------------------------------------
    private void handleGameOver(ACLMessage msg) {
        System.out.println("\n" + getLocalName() + " received 'game-over' from " 
                           + msg.getSender().getLocalName()
                           + " with content: " + msg.getContent());
        // Perform any final cleanup or stats saving here if needed.

        // Terminate this agent
        doDelete();
    }
    
    private void handlePriceAnnouncement(ACLMessage msg) {
        System.out.println(getLocalName() + " received price-announcement:\n  " + msg.getContent());
        decodePriceEventInfo(msg.getContent());
    }

    private void handleSellRequest(ACLMessage msg) {
        System.out.println(getLocalName() + " received sell-request from " + msg.getSender().getLocalName());
        
        // Store this message, but do not necessarily reply right away
        pendingSellRequest = msg;

        // If negotiation is already closed (either success or fallback),
        // we can respond immediately
        if (!negotiationOpen) {
            sendSellResponse();
        }
    }

    /**
     * Called when a negotiation is successful (trade accepted).
     * We close negotiation and if we have a pending sell-request, we respond now.
     */
    private void onNegotiationSuccess() {
        negotiationOpen = false;
        // If we have a pending sell request, respond
        if (pendingSellRequest != null) {
            sendSellResponse();
        }
    }

    /**
     * Build and send the sell response. Then reset any local round-based states if needed.
     */
    private void sendSellResponse() {
        // Decide how many to sell
        Map<String, Integer> sellDecision = decideSales(currentPrices, inventory, currentEvent);
        String sellContent = encodeSellInfo(sellDecision);

        ACLMessage reply = pendingSellRequest.createReply();
        reply.setPerformative(ACLMessage.INFORM);
        reply.setConversationId("sell-response");
        reply.setContent(sellContent);
        send(reply);

        System.out.println(getLocalName() + " -> sell-response: " + sellContent);

        // Update local inventory
        updateLocalInventory(sellDecision);

        // Clear out the pending request for next round
        pendingSellRequest = null;
    }

    // ---------------------------------------------------------------------
    //  Negotiation / Alliance Logic
    // ---------------------------------------------------------------------

    /**
     * Decide the content of a trade proposal: what we offer vs. what we request.
     * Possibly factor in alliances or rivalries for ratio changes.
     */
    private void decideWhatToOfferAndRequest(
            Map<String, Integer> offered, 
            Map<String, Integer> requested,
            AID partner) 
    {
        // Example logic: pick the spice we have the most of and
        // ask for the spice with the highest price.

        // 1) Which spice do we have the most of?
        String maxSpice = null; 
        int maxQty = 0;
        for (Map.Entry<String, Integer> e : inventory.entrySet()) {
            if (e.getValue() > maxQty) {
                maxQty = e.getValue();
                maxSpice = e.getKey();
            }
        }
        // 2) Which spice has the highest price?
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
            int toOffer = Math.min(2, inventory.get(maxSpice));

            // Allies get more favorable ratio
            // Rivals get a tough ratio
            // Neutral is in-between
            if (allies.contains(partner)) {
                offered.put(maxSpice, toOffer);
                requested.put(highPriceSpice, 1);
            } else if (rivals.contains(partner)) {
                offered.put(maxSpice, toOffer);
                requested.put(highPriceSpice, toOffer); 
            } else {
                offered.put(maxSpice, toOffer);
                requested.put(highPriceSpice, (int)Math.ceil(toOffer / 2.0));
            }
        }
    }

    /**
     * Check if the trade is acceptable:
     *  1) We have enough of the requested items
     *  2) Compare approximate "value" from currentPrices
     *  3) Adjust acceptance threshold for allies or rivals
     */
    private boolean isTradeAcceptable(Map<String, Integer> offered, 
                                      Map<String, Integer> requested,
                                      AID proposer) {
        // Check inventory feasibility
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int needed = req.getValue();
            int current = inventory.getOrDefault(spice, 0);
            if (needed > current) {
                return false;
            }
        }

        // Sum up approximate value
        int valueOffered = 0;
        for (Map.Entry<String, Integer> off : offered.entrySet()) {
            String spice = off.getKey();
            int qty = off.getValue();
            int price = currentPrices.getOrDefault(spice, 0);
            valueOffered += (qty * price);
        }
        int valueRequested = 0;
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qty = req.getValue();
            int price = currentPrices.getOrDefault(spice, 0);
            valueRequested += (qty * price);
        }

        // Allies might accept slightly unfavorable deals
        if (allies.contains(proposer)) {
            return (valueOffered >= valueRequested * 0.8);
        }
        // Rivals might require strictly better deals
        if (rivals.contains(proposer)) {
            return (valueOffered >= valueRequested * 1.2);
        }
        // Otherwise, standard check
        return (valueOffered >= valueRequested);
    }

    /**
     * Apply the trade:
     *  - We gain the offered items
     *  - We lose the requested items
     */
    private void executeTrade(Map<String, Integer> offered, Map<String, Integer> requested) {
        // Add offered to our inventory
        for (Map.Entry<String, Integer> off : offered.entrySet()) {
            String spice = off.getKey();
            int qty = off.getValue();
            int current = inventory.getOrDefault(spice, 0);
            inventory.put(spice, current + qty);
        }
        // Remove requested from our inventory
        for (Map.Entry<String, Integer> req : requested.entrySet()) {
            String spice = req.getKey();
            int qty = req.getValue();
            int current = inventory.getOrDefault(spice, 0);
            inventory.put(spice, Math.max(0, current - qty));
        }
    }

    // ---------------------------------------------------------------------
    //  Selling Logic (post-negotiation)
    // ---------------------------------------------------------------------
    private Map<String, Integer> decideSales(
            Map<String, Integer> prices, 
            Map<String, Integer> myInventory, 
            String eventDesc)
    {
        // Example logic: if price >= 10, sell half. If there's a "storm" on Cloves, hold them, etc.
        Map<String, Integer> decision = new HashMap<>();
        for (String spice : myInventory.keySet()) {
            int quantity = myInventory.get(spice);
            int price = prices.getOrDefault(spice, 0);

            if (eventDesc.toLowerCase().contains("storm") && spice.equals(BazaarAgent.CLOVE)) {
                // If we suspect next round's Clove price might jump, hold
                decision.put(spice, 0);
            }
            else if (price >= 10) {
                // Sell half
                decision.put(spice, quantity / 2);
            } else {
                // Otherwise, hold
                decision.put(spice, 0);
            }
        }
        return decision;
    }

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
    //  Utility: Encode / Decode
    // ---------------------------------------------------------------------
    private String encodeTradeProposal(Map<String, Integer> offered, Map<String, Integer> requested) {
        // "OFFER:spiceA=2;spiceB=1 -> REQUEST:spiceC=1;spiceD=2"
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

    private void decodeTradeProposal(String content, Map<String, Integer> offered, Map<String, Integer> requested) {
        // e.g. "OFFER:Cardamom=2;Cinnamon=1 -> REQUEST:Clove=1;Nutmeg=1"
        try {
            String[] parts = content.split("->");
            if (parts.length == 2) {
                String offerPart = parts[0].replace("OFFER:", "").trim();
                String requestPart = parts[1].replace("REQUEST:", "").trim();

                String[] offerItems = offerPart.split(";");
                for (String o : offerItems) {
                    String[] kv = o.split("=");
                    if (kv.length == 2) {
                        offered.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
                    }
                }
                String[] requestItems = requestPart.split(";");
                for (String r : requestItems) {
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

    private String encodeSellInfo(Map<String, Integer> sellMap) {
        // e.g. "Clove=2;Cinnamon=1;Nutmeg=0;Cardamom=3"
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : sellMap.entrySet()) {
            sb.append(e.getKey()).append("=").append(e.getValue()).append(";");
        }
        if (sb.length() > 0) {
            sb.setLength(sb.length() - 1); // remove trailing semicolon
        }
        return sb.toString();
    }

    private void decodePriceEventInfo(String content) {
        // e.g. "Clove=20;Cinnamon=10;Nutmeg=15;Cardamom=5|EVENT:Storm..."
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
            currentEvent = mainParts[1].trim();
        }
    }
}
