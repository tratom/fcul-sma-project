package project;

import java.util.HashMap;
import java.util.Random;

import jade.core.AID;
import jade.core.Agent;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.core.behaviours.CyclicBehaviour;

public class MerchantAgent extends Agent{
    private static final long serialVersionUID = 1L;
	private HashMap<String, Integer> inventory = new HashMap<String, Integer>();
	private int budget = 100;
    private Random random = new Random();

    
    protected void setup() {
        registerDF();
        System.out.println("Merchant Agent " + getLocalName() + " started.");
		initInventory();
        addBehaviour(new MerchantBehaviour());
    }

    private void registerDF() {
    	DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("Merchant");
		sd.setName(getLocalName());
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
		} catch (FIPAException fe) {
			fe.printStackTrace();
		}
    }
    
    

	private void initInventory() {
		inventory.put("Clove", random.nextInt(3) + 1);
        inventory.put("Cinnamon", random.nextInt(5) + 1);
        inventory.put("Nutmeg", random.nextInt(3) + 1);
        inventory.put("Cardamom", random.nextInt(4) + 1);
	}

	private class MerchantBehaviour extends CyclicBehaviour {

		@Override
        public void action() {
			System.out.println("[" + getLocalName() + "] Budget:["+budget+"] | Inventory: "+inventory);
            ACLMessage msg = receive();
            if (msg != null) {
                switch (msg.getPerformative()) {
                    case ACLMessage.REQUEST:
                    	handleAction(msg);
                        break;
                    case ACLMessage.PROPOSE:
                        handleNegotiation(msg);
                        break;
                }
            } else {
                block();
            }
        }

        private void handleAction(ACLMessage msg) {
            System.out.println("[" + getLocalName() + "] received a round action request.");
            
            if(random.nextBoolean()) {
            	trade();
            }else {
                System.out.println("[" + getLocalName() + "]: Chooses not to trade.");
            }
            

            if (random.nextBoolean()) {
                sellToMarket();
            } else {
                System.out.println("[" + getLocalName() + "]: Chooses to hold inventory.");
            }
        }
		
		private void trade() {
            AID targetMerchant = searchDF("Merchant");
            if(targetMerchant != null) {
            	ACLMessage proposal = new ACLMessage(ACLMessage.PROPOSE);
                proposal.addReceiver(targetMerchant);
                proposal.setContent("Exchange 1 Nutmeg for 2 Cloves");
                send(proposal);
                System.out.println("[" + getLocalName() + "]: Proposed a trade to " + targetMerchant.getLocalName());
            }else {
            	System.out.println("[" + getLocalName() + "]: Could not find another merchant for trade.");
            }          
        }
		
		private void handleNegotiation(ACLMessage msg) {
            System.out.println("[" + getLocalName() + "]: Received a trade proposal: " + msg.getContent());

            // Evaluate the proposal
            if (random.nextBoolean()) {
                ACLMessage accept = msg.createReply();
                accept.setPerformative(ACLMessage.ACCEPT_PROPOSAL);
                accept.setContent("Accepted: " + msg.getContent());
                send(accept);

                // Update inventory to reflect trade
                inventory.put("Nutmeg", inventory.get("Nutmeg") - 1);
                inventory.put("Clove", inventory.get("Clove") + 2);
                System.out.println("[" + getLocalName() + "] accepted the trade and updated inventory.");
            } else {
                ACLMessage reject = msg.createReply();
                reject.setPerformative(ACLMessage.REJECT_PROPOSAL);
                reject.setContent("Rejected: " + msg.getContent());
                send(reject);
                System.out.println("[" + getLocalName() + "] rejected the trade.");
            }
        }
		
		private void sellToMarket() {
			AID gameMaster = searchDF("GameMaster");
			if(gameMaster != null) {
				String spiceToSell = "Clove";
				
				//Request Spice Price
				ACLMessage priceRequest = new ACLMessage(ACLMessage.REQUEST);
			    priceRequest.addReceiver(gameMaster);
			    priceRequest.setContent("MarketPrice:" + spiceToSell);
			    send(priceRequest);
			    	    
			    //Response
			    ACLMessage response = blockingReceive();
			    if(response != null && response.getPerformative() == ACLMessage.INFORM) {
			    	try {
			    		float marketPrice = Float.parseFloat(response.getContent());
			    		int quantityToSell = Math.min(inventory.get(spiceToSell), random.nextInt(3) + 1);
			    		inventory.put(spiceToSell, inventory.get(spiceToSell) - quantityToSell);
			    		float totalPrice = quantityToSell * marketPrice;
			    		budget += totalPrice;
			    		System.out.println("[" + getLocalName() + "]: Sold " + quantityToSell + " " + spiceToSell + " for " + totalPrice + " coins at " + marketPrice + " per unit.");
			    	}catch(NumberFormatException e) {
			    		System.out.println("[" + getLocalName() + "]: Received an invalid market price response.");
			    	}
			    }else {
			    	System.out.println("[" + getLocalName() + "]: Did not receive market price information.");
			    }
			}else {
				System.out.println("[" + getLocalName() + "]: Could not find the GameMaster for market prices.");
			}
        }
		
		private AID searchDF(String role) {
            try {
                DFAgentDescription template = new DFAgentDescription();
                ServiceDescription sd = new ServiceDescription();
                sd.setType(role);
                template.addServices(sd);
                DFAgentDescription[] result = DFService.search(myAgent, template);
                for (DFAgentDescription df : result) {
                    if (!df.getName().equals(getAID())) {
                        return df.getName();
                    }
                }
            } catch (FIPAException e) {
                e.printStackTrace();
            }
            return null;
        }	
	}
}
