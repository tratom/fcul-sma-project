package zanzibar.bazaar;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class MerchantAgent extends Agent {

	private HashMap<String, Integer> inventory = new HashMap<>();
	private HashMap<String, Integer> currentPrices = new HashMap<>();
	private int budget = 100;
	private int negotiationAttempts = 0;
    private static final int MAX_NEGOTIATION_ATTEMPTS = 3;
	private boolean negotiationOpen = false;

	private ACLMessage pendingSellRequest = null;

	private Random random = new Random();

	@Override
	protected void setup() {
		registerDF();
		initInventory();
		addBehaviour(new MerchantBehaviour());
		addBehaviour(new NegotiationResponderBehaviour());
		addBehaviour(new NegotiationMakerBehaviour(this, 5000));
	}

	private class MerchantBehaviour extends CyclicBehaviour {

		@Override
		public void action() {
			ACLMessage msg = receive();
			if (msg != null) {
				String convoId = msg.getConversationId();
				switch (convoId) {
				case "round-start":
					System.out.println("[" + getLocalName() + "] Budget:[" + budget + "] | Inventory: " + inventory);
					break;
				case "price-announcement":
					handlePriceChanges(msg);
					negotiationOpen = true;
					break;
				case "sell-request":
					handleSellRequest(msg);
					break;
				case "game-over":
					handleGameOver(msg);
					break;
				}
			} else {
				block();
			}
		}
	}

	private class NegotiationMakerBehaviour extends TickerBehaviour {

		public NegotiationMakerBehaviour(Agent a, long period) {
			super(a, period);
		}

		@Override
		protected void onTick() {
			if (negotiationOpen) {
				negotiationAttempts++;
				if(negotiationAttempts <= MAX_NEGOTIATION_ATTEMPTS) {
					makeTrade();
				}else {
					negotiationOpen = false;
					 if (pendingSellRequest != null) {
		                    sellResponse();
		             }
					 return;
				}		
			} else {
				return;
			}
		}

	}

	private class NegotiationResponderBehaviour extends CyclicBehaviour {

		@Override
		public void action() {
			MessageTemplate mt = MessageTemplate.MatchConversationId("trade-offer");
			ACLMessage msg = myAgent.receive(mt);
			if (msg != null && msg.getPerformative() == ACLMessage.PROPOSE) {
				handleNegotiation(msg);
			} else {
				block();
			}

		}

	}

	// ---------------------------------------------------------------------
	// Other Functions
	// ---------------------------------------------------------------------

	private void initInventory() {
		inventory.put("Clove", random.nextInt(3) + 1);
		inventory.put("Cinnamon", random.nextInt(5) + 1);
		inventory.put("Nutmeg", random.nextInt(3) + 1);
		inventory.put("Cardamom", random.nextInt(4) + 1);
	}

	private void buildReply(ACLMessage msg, int perf, String converstionID, String content) {
		ACLMessage reply = msg.createReply();
		reply.setPerformative(perf);
		reply.setConversationId(converstionID);
		reply.setContent(content);
		send(reply);
	}

	private String chooseRandomSpice() {
		return (String) inventory.keySet().toArray()[random.nextInt(inventory.size())];
	}

	private void updateLocalInventory(HashMap<String, Integer> sellDecision) {
		for (HashMap.Entry<String, Integer> entry : sellDecision.entrySet()) {
			String spice = entry.getKey();
			int sellQty = entry.getValue();
			int currentQty = inventory.get(spice);
			int newQty = Math.max(0, currentQty - sellQty);
			inventory.put(spice, newQty);
		}
	}

	// ---------------------------------------------------------------------
	// DF Search & Register
	// ---------------------------------------------------------------------
	private void registerDF() {
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("zanzibar-player");
		sd.setName(getLocalName());
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
			System.out.println("Merchant Agent " + getLocalName() + " started.");
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
	}

	private AID searchDF(String role) {
		try {
			DFAgentDescription template = new DFAgentDescription();
			ServiceDescription sd = new ServiceDescription();
			sd.setType(role);
			template.addServices(sd);
			DFAgentDescription[] result = DFService.search(this, template);
			for (DFAgentDescription df : result) {
				if (!df.getName().equals(getAID())) {
					System.out.println("[" + getLocalName() + "]: Discovered " + df.getName().getLocalName());
					return df.getName();
				}
			}

		} catch (FIPAException e) {
			e.printStackTrace();
		}
		return null;
	}

	// ---------------------------------------------------------------------
	// GameMaster Handlers
	// ---------------------------------------------------------------------
	private void handleGameOver(ACLMessage msg) {
		System.out.println("[" + getLocalName() + "]: Received 'game-over' from GameMaster ["
				+ msg.getSender().getLocalName() + "]");
		System.out.println("[" + getLocalName() + "]: Ended Game with " + budget + " points");
		doDelete();
	}

	private void handlePriceChanges(ACLMessage msg) {
		System.out.println("[" + getLocalName() + "]: Received price-announcement:\n  " + msg.getContent());
		decodePriceEventInfo(msg.getContent());
	}

	private void handleSellRequest(ACLMessage msg) {
		System.out.println(
				"[" + getLocalName() + "]: Received sell-request from [" + msg.getSender().getLocalName() + "]");
		pendingSellRequest = msg;
		if (!negotiationOpen) {
			sellResponse();
		}
	}

	// ---------------------------------------------------------------------
	// Players Handlers
	// ---------------------------------------------------------------------

	private void handleNegotiation(ACLMessage msg) {
		System.out.println("[" + getLocalName() + "]: Received a Trade Proposal: " + msg.getContent());
		if (random.nextBoolean()) {
			System.out.println("[" + getLocalName() + "]: Accepted the Trade Proposal");
			acceptedTrade(msg);
			negotationCompleted();
		} else {
			System.out.println("[" + getLocalName() + "]: Rejected the Trade Proposal");
			buildReply(msg, ACLMessage.REJECT_PROPOSAL, "trade-offer", "Trade Rejected");
		}

	}

	private void negotationCompleted() {
		negotiationOpen = false;
		if (pendingSellRequest != null) {
			sellResponse();
		}
	}

	// ---------------------------------------------------------------------
	// Negotiation Functions
	// ---------------------------------------------------------------------
	private void acceptedTrade(ACLMessage msg) {
		HashMap<String, Integer> offered = new HashMap<>();
		HashMap<String, Integer> requested = new HashMap<>();
		decodeTradeProposal(msg.getContent(), offered, requested);
		executeTrade(offered, requested);
		buildReply(msg, ACLMessage.ACCEPT_PROPOSAL, "trade-offer", "Trade Accepted");
		System.out.println("[" + getLocalName() + "]: Accepted trade with " + msg.getSender().getLocalName() + ": "
				+ msg.getContent());
	}

	private void executeTrade(HashMap<String, Integer> offered, HashMap<String, Integer> requested) {
		// Add offered to our inventory
		for (HashMap.Entry<String, Integer> off : offered.entrySet()) {
			String spice = off.getKey();
			int qty = off.getValue();
			int current = inventory.get(spice);
			inventory.put(spice, current + qty);
		}
		// Remove requested from our inventory
		for (HashMap.Entry<String, Integer> req : requested.entrySet()) {
			String spice = req.getKey();
			int qty = req.getValue();
			int current = inventory.get(spice);
			inventory.put(spice, Math.max(0, current - qty));
		}
	}

	private void makeTrade() {
		AID targetMerchant = searchDF("zanzibar-player");
		if (targetMerchant != null) {
			String spiceOffer = chooseRandomSpice();
			String spiceRequest = chooseRandomSpice();

			// Ensure inventory has enough of the offered spice
			if (!spiceOffer.equalsIgnoreCase(spiceRequest) && inventory.get(spiceOffer) > 0) {
				int qtyOffer = random.nextInt(inventory.get(spiceOffer)) + 1;
				int qtyRequest = random.nextInt(5) + 1;

				HashMap<String, Integer> offered = new HashMap<>();
				HashMap<String, Integer> requested = new HashMap<>();
				offered.put(spiceOffer, qtyOffer);
				requested.put(spiceRequest, qtyRequest);

				String content = encodeTradeProposal(offered, requested);
				ACLMessage proposal = new ACLMessage(ACLMessage.PROPOSE);
				proposal.setConversationId("trade-offer");
				proposal.addReceiver(targetMerchant);
				proposal.setContent(content);

				send(proposal);
				System.out.println("[" + getLocalName() + "]: Proposed trade: " + content);
			} else {
				makeTrade();
			}
		} else {
			System.out.println("[" + getLocalName() + "]: Could not find another merchant for trade.");
		}
	}

	// ---------------------------------------------------------------------
	// Sell Functions
	// ---------------------------------------------------------------------
	private void sellResponse() {
		HashMap<String, Integer> decision = new HashMap<>();

		if (random.nextBoolean()) {
			decision = sellToMarket(decision);
		} else {
			System.out.println("[" + getLocalName() + "]: Doesn't Want to Sell");
			for (String spice : inventory.keySet()) {
				decision.put(spice, 0);
			}
		}
		String content = encodeSellInfo(decision);
		buildReply(pendingSellRequest, ACLMessage.INFORM, "sell-response", content);
		updateLocalInventory(decision);
		pendingSellRequest = null;

	}

	private HashMap<String, Integer> sellToMarket(HashMap<String, Integer> info) {
		String spice = chooseRandomSpice();
		while (inventory.get(spice) == 0) {
			spice = chooseRandomSpice();
		}

		int availableQty = inventory.get(spice);
		int qtyToSell = random.nextInt(availableQty) + 1;
		int marketPrice = currentPrices.get(spice);

		int earnings = qtyToSell * marketPrice;
		budget += earnings;
		for (String spice1 : inventory.keySet()) {
			if (spice1.equals(spice)) {
				info.put(spice1, qtyToSell);
			} else {
				info.put(spice1, 0);
			}
		}

		System.out.println("[" + getLocalName() + "]: Sold " + qtyToSell + " " + spice + " at " + marketPrice
				+ " each. Earned: " + earnings + " | New Budget: " + budget);

		return info;
	}

	// ---------------------------------------------------------------------
	// Utility: Encode / Decode Functions
	// ---------------------------------------------------------------------
	private String encodeTradeProposal(HashMap<String, Integer> offered, HashMap<String, Integer> requested) {
		// "OFFER:spiceA=2;spiceB=1 -> REQUEST:spiceC=1;spiceD=2"
		StringBuilder sb = new StringBuilder();
		sb.append("OFFER:");
		boolean first = true;
		for (Map.Entry<String, Integer> e : offered.entrySet()) {
			if (!first) {
				sb.append(";");
			}
			sb.append(e.getKey()).append("=").append(e.getValue());
			first = false;
		}
		sb.append(" -> REQUEST:");
		first = true;
		for (Map.Entry<String, Integer> e : requested.entrySet()) {
			if (!first) {
				sb.append(";");
			}
			sb.append(e.getKey()).append("=").append(e.getValue());
			first = false;
		}
		return sb.toString();
	}

	private void decodeTradeProposal(String content, HashMap<String, Integer> offered,
			HashMap<String, Integer> requested) {
		try {
			if (content == null || content.isEmpty()) {
				throw new IllegalArgumentException("Trade proposal content is empty or null.");
			}

			String[] parts = content.split("->");
			if (parts.length != 2) {
				throw new IllegalArgumentException("Trade proposal format is invalid: " + content);
			}

			String offerPart = parts[0].replace("OFFER:", "").trim();
			String requestPart = parts[1].replace("REQUEST:", "").trim();

			for (String o : offerPart.split(";")) {
				String[] kv = o.split("=");
				if (kv.length == 2) {
					offered.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
				}
			}
			for (String r : requestPart.split(";")) {
				String[] kv = r.split("=");
				if (kv.length == 2) {
					requested.put(kv[0].trim(), Integer.parseInt(kv[1].trim()));
				}
			}
		} catch (Exception e) {
			System.err.println(getLocalName() + ": Error decoding trade proposal: " + content);
			e.printStackTrace();
		}
	}

	private String encodeSellInfo(HashMap<String, Integer> sellMap) {
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
			mainParts[1].trim();
		}
	}
}
