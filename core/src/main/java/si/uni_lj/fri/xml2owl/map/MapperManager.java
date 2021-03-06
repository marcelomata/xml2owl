package si.uni_lj.fri.xml2owl.map;

import java.util.*;

import net.sf.saxon.s9api.*;
import org.semanticweb.owlapi.model.*;
import org.semanticweb.owlapi.io.OWLObjectRenderer;
import uk.ac.manchester.cs.owlapi.dlsyntax.DLSyntaxObjectRenderer;
import org.semanticweb.owlapi.util.InferredOntologyGenerator;
import org.semanticweb.owlapi.util.InferredAxiomGenerator;
import com.clarkparsia.pellet.owlapiv3.PelletReasoner;
import com.clarkparsia.pellet.owlapiv3.PelletReasonerFactory;
 
import si.uni_lj.fri.xml2owl.util.*;
import si.uni_lj.fri.xml2owl.rules.RulesValidator;
import si.uni_lj.fri.xml2owl.rules.Xml2OwlRuleValidationException;

/** This class handles the data, rules and Mapper and defers to the
 * Mapper to map each rule. */
public class MapperManager {

    /** Evaluator used to evaluate XPath expressions for rules. */
    private final XPathEvaluator rulesEvaluator;

    /** Evaluator used to evaluate XPath expressions for data. */
    private final XPathEvaluator dataEvaluator;

    /** Mapper used to process the mapping rules. */
    private Mapper mapper;

    /** Iterator for the set of mapping rules to process. */
    private XdmSequenceIterator ruleIterator; 

    /** Flag indicating whether there has been a lethal exception. */
    private boolean abort;

    /** The axioms added to the OWL ontology in the most recent 'map' call. */
    private Set<OWLAxiom> lastChanges;

    /** Constructor. */
    public MapperManager(Processor xmlProcessor) {
	rulesEvaluator = new XPathEvaluator(xmlProcessor, 
                                            "http://www.fri.uni-lj.si/xml2owl");
	dataEvaluator = new XPathEvaluator(xmlProcessor, "");
	mapper = null;
	ruleIterator = null;
	abort = false;
    }

   /**  Get the data into the right format, create a Mapper, prepare
     * the set of rules, and iterate through the rules, processing one
     * at a time.  Also handle resulting XML2OWL and Saxon
     * exceptions. */ 
    public OWLOntology map (OWLOntologyManager owlManager, 
                            XdmNode rules, 
                            OWLOntology owl, 
                            XdmNode xml)
        throws Xml2OwlMapException {
	try {
            System.out.println("[XML2OWL] Beginning ruleset mapping ...");
            addNamespaces(rules);
            MapperParameters parameters = extractParameters(rules);
            PelletReasoner reasoner = PelletReasonerFactory.getInstance().createNonBufferingReasoner(owl);
            reasoner.prepareReasoner();
            owlManager.addOntologyChangeListener(reasoner);
            List<String> referenceNames = findReferenceNames(rules);
            mapper = new Mapper(owlManager, owl, xml, // 
                                parameters, rulesEvaluator, dataEvaluator, reasoner, referenceNames);
            ruleIterator = rulesEvaluator.findIterator
                (rules, "*[name() = 'prefixIRI' or starts-with(name(),'mapTo') or name() = 'collectOWLIndividuals']");
            processRules();
            reasoner.getKB().realize();
            InferredOntologyGenerator generator = new InferredOntologyGenerator(reasoner);
            generator.fillOntology(owlManager, owl);
            OWLObjectRenderer renderer = new DLSyntaxObjectRenderer(); 
            for (SWRLRule rule : owl.getAxioms(AxiomType.SWRL_RULE)) { 
                System.out.println("[XML2OWL] Processing SWRL rule: " + renderer.render(rule) + " ..."); 
            }
            lastChanges = mapper.getAxiomsAdded();
            for (InferredAxiomGenerator axiomGenerator : generator.getAxiomGenerators()) {
                lastChanges.addAll(axiomGenerator.createAxioms(owlManager, reasoner));
            }
            System.out.println("[XML2OWL] Ruleset mapping successfully completed.");
	}
	catch (SaxonApiException e) {
	    handleException(e);
	}
	catch (Xml2OwlMappingException e) {
	    handleException(e);
	}
         return owl;
     }

    /** Undo all changes made to the OWL ontology with this Mapper. */
    public void unmap(OWLOntologyManager owlManager, OWLOntology owl) {
        owlManager.removeAxioms(owl, lastChanges);
    }

    /** Add the namespaces defined in the rules to the dataEvaluator. */
    private void addNamespaces(XdmNode rules) throws SaxonApiException {
         XdmSequenceIterator namespaceIterator = 
             rulesEvaluator.findIterator(rules,"namespaces/namespace");
        while (namespaceIterator.hasNext()) {
            XdmNode node = (XdmNode) namespaceIterator.next();
            String prefix = rulesEvaluator.findString(node, "@prefix");
            String name = rulesEvaluator.findString(node, "@name");
            dataEvaluator.addNamespace(prefix,name);
        }
    }

    /** Find all the reference names in the rules. */
    private List<String> findReferenceNames(XdmNode rules) throws SaxonApiException {
        List<String> list = new ArrayList<String>();
         XdmSequenceIterator referenceIterator = 
             rulesEvaluator.findIterator(rules,"//@referenceName");
        while (referenceIterator.hasNext()) {
            list.add(referenceIterator.next().getStringValue());
        }
        return list;
    }

     /** Extract mapping parameters from the input rules. */
     private MapperParameters extractParameters(XdmNode rules)  
	 throws SaxonApiException, Xml2OwlMappingException {
	 MapperParameters parameters = new MapperParameters();
	 parameters.setQueryLanguage
	     (rulesEvaluator.findString
	      (rules, "@queryLanguage"));
	 parameters.setExpressionLanguage
	     (rulesEvaluator.findString
	      (rules, "@expressionLanguage"));
	 parameters.setStrict
	     (Boolean.parseBoolean
	      (rulesEvaluator.findString(rules,"@strict")));
        return parameters;
     }

     /** Process the rules remaining in ruleIterator, handling exceptions if
	 necessary. */
    public void processRules() throws Xml2OwlMapException {
	while (!abort && ruleIterator.hasNext()) {
            try {
                mapper.mapRule((XdmNode) ruleIterator.next());
            }
            catch (SaxonApiException e) {
                handleException(e);
            }
            catch (Xml2OwlMappingException e) {
                handleException(e);
            }
        }
    }

    /** Handle an exception.  Set the abort flag, unless it's a
     * non-lethal XML2OWLMappingException. */
    private void handleException(Exception exception) throws Xml2OwlMapException {
        abort = true; // default
	String prefix = "[XML2OWL] ";
	if (exception instanceof SaxonApiException) {
	    prefix += "Saxon exception: ";
	} else if (exception instanceof OWLException) {
	    prefix += "OWL exception: ";
	} else if (exception instanceof Xml2OwlMappingException) {
	    if (((Xml2OwlMappingException) exception).isLethal()) {
		prefix += "XML2OWL mapping exception: ";
	    } else { // it's OK if it's a non-lethal XML2OWL exception
		prefix += "XML2OWL mapping warning: ";
		abort = false; 
	    }
	} else if (!(exception instanceof Xml2OwlMapException)) {
	    prefix += "Unrecognised exception of type " + exception.getClass().getName() + " : ";
	}
	String currentMessage = prefix + exception.getMessage();
        System.out.println(currentMessage);
	if (abort) {
            abort = false; // so next map attempt is not blocked  
	    throw new Xml2OwlMapException(currentMessage);
        }
    }

}
