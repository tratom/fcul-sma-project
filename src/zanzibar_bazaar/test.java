package zanzibar_bazaar;

import jade.core.Agent;
import jade.core.behaviours.ThreadedBehaviourFactory;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;

public class test extends Agent {
    private ThreadedBehaviourFactory tbf;

    @Override
    protected void setup() {
        System.out.println("Hello, the configuration works! GoodBye.");
        doDelete();
        return;
    }

    @Override
    protected void takeDown() {
        System.out.println(getLocalName() + " shutting down.");
    }
}