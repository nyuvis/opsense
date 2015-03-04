package edu.nyu.vgc.opsense.extraction;

import edu.nyu.vgc.opsense.extraction.GraphBuilder.RelationshipEdge;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.ling.IndexedWord;
import edu.stanford.nlp.ling.TaggedWord;
import edu.stanford.nlp.process.DocumentPreprocessor;
import edu.stanford.nlp.process.Morphology;
import edu.stanford.nlp.tagger.maxent.MaxentTagger;
import edu.stanford.nlp.trees.GrammaticalStructure;
import edu.stanford.nlp.trees.TypedDependency;

import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.jgrapht.DirectedGraph;


public class Parser {
	
	private String modelsDir = "/Users/cristian/Developer/models/nlp/";
	private String modelPath = modelsDir + "parser/PTB_Stanford_params.txt.gz";
    private String taggerPath = modelsDir + "tagger/english-left3words-distsim.tagger";
    private String classPath = modelsDir + "classifiers/english.all.3class.distsim.crf.ser.gz";
	
    MaxentTagger tagger = new MaxentTagger(taggerPath);
    DpParser parser = DpParser.loadFromModelFile(modelPath);
    AbstractSequenceClassifier<CoreLabel> classifier;
    GraphBuilder gBuilder = new GraphBuilder();
    
    Set<String> rlnDiscard = new HashSet<String>(Arrays.asList("det", "cc", "root", "punct", "expl", "discourse", "preconj", "predet"
			, "num","csubjpass","pcomp", "mark", "agent", "npadvmod","dep", "csubj", "iobj", "pobj", "parataxis","tmod", "infmod", "appos", "partmod",
			"prepc"));
	Set<String> rlnDirectLink = new HashSet<String>(Arrays.asList(
			"ccomp",
			//"dobj",
			"advcl",
			"prepc",
			"neg",
			"infmod",
			"partmod",
			"appos",
			"advmod",
			"nsubj",
			"rcmod",
			"conj",
			//"poss",
			//"prep",
			"nn",
			"amod"
			));
	Set<String> POSNodes = new HashSet<String>(Arrays.asList(
			"JJ","JJR","JJS", 					//adjectives
			"NN","NNS","NNP","NNPS", 			//Nouns
			"PRP","PRP$", 						//Pronoun
			"RB","RBR","RBS","RP", 				//Adverb
			"VB","VBD","VBG","VBN","VBP","VBZ"  //Verbs
			));
	Set<String> NounNodes = new HashSet<String>(Arrays.asList(		
			"NN","NNS","NNP","NNPS", 			//Nouns
			"PRP","PRP$" 						//Pronoun
			));
    
    public AbstractSequenceClassifier<CoreLabel> classifier() {
    	return this.classifier;
	}
    
    public Parser(){
    	try {
			classifier = CRFClassifier.getClassifier(classPath);
		} catch (ClassCastException | ClassNotFoundException | IOException e) {
			e.printStackTrace();
		}
    }
    
    private static final Morphology m = new Morphology(); 
    
    public final static String lemma(String tag, String word){
        List<String> noLemma = Arrays.asList("wifi");
        if(noLemma.contains(word))
            return word;
        return m.lemma(word, tag).toLowerCase();
    }
  
    private void selectEntites(List<TaggedWord> sentence, HashMap<String, String> entities) {
    	classifier.classifySentence(sentence)
		.stream()
		.filter(w -> !w.get(CoreAnnotations.AnswerAnnotation.class).equals("O"))
		.forEach(cl -> {
    		String entity = cl.get(CoreAnnotations.AnswerAnnotation.class);
    		entities.put(cl.word() + cl.beginPosition() + cl.endPosition(), entity);
    	});
	}
    
    private List<CoreLabel> getEntites(List<TaggedWord> sentence, int sentIdx) {
    	List<CoreLabel> entities = classifier.classifySentence(sentence)
			.stream()
			.filter(w -> !w.get(CoreAnnotations.AnswerAnnotation.class).equals("O"))
			.collect(Collectors.toList());
    	entities.forEach(cl -> {
	    		String entity = cl.get(CoreAnnotations.AnswerAnnotation.class);
	    		cl.setNER(entity);
	    		cl.setSentIndex(sentIdx);
	    	});
    	return entities;
	}
    
    public List<TypedDependency>  selectRelations (String document) {
		DocumentPreprocessor tokenizer = new DocumentPreprocessor(new StringReader(document));
		List<TypedDependency> allDependencies = new LinkedList();
		int idx = 1;
		for (List<HasWord> sentence : tokenizer) {
	    	List<TaggedWord> tagged = tagger.tagSentence(sentence); //TAG SENTENCE
	    	GrammaticalStructure gs = parser.predict(tagged, idx++);
	    	List<CoreLabel> entities = getEntites(tagged, idx);
	    	
	    	List<TypedDependency> dependencies = gs.typedDependenciesCCprocessed()
	    			.stream().filter(ts -> 
	    					(NounNodes.contains(ts.dep().tag()) || 
	    					NounNodes.contains(ts.gov().tag())) &&
	    					rlnDirectLink.contains(ts.reln().getShortName()))
	    			.collect(Collectors.toList());
	    	
	    	dependencies.forEach(dp -> {
	    		setLemmaIfNot(dp.gov(), entities);
	    		setLemmaIfNot(dp.dep(), entities);
	    	});
	    	allDependencies.addAll(dependencies);
	    }
		return allDependencies;
    }
    
    private void setLemmaIfNot(IndexedWord word, List<CoreLabel> entities) {
    	if(word.lemma() != null)
    		return;
    	
    	String lemma = entities.stream()
    			.filter(e -> e.word() == word.word())
    			.map(w -> w.ner()).findFirst().orElse(null);
    	if(lemma == null)
    		lemma = lemma(word.tag(), word.word());
    	word.setLemma(lemma);
    }
           
	public DirectedGraph<IndexedWord,RelationshipEdge<IndexedWord>> parse(String document) {
		DocumentPreprocessor tokenizer = new DocumentPreprocessor(new StringReader(document));
		List<GrammaticalStructure> result = new LinkedList<GrammaticalStructure>();
		HashMap<String, String> entities = new HashMap<>();
		int idx = 1;
	    for (List<HasWord> sentence : tokenizer) {
	    	List<TaggedWord> tagged = tagger.tagSentence(sentence);
	    	GrammaticalStructure gs = parser.predict(tagged, idx++);
	    	selectEntites(tagged, entities);
	    	result.add(gs);
	    }
	    DirectedGraph<IndexedWord, RelationshipEdge<IndexedWord>> graph = gBuilder.getGraph(result);

	    graph.vertexSet().forEach(d -> {
	    	String lemma = entities.get(d.word()+d.beginPosition()+d.endPosition()); 
	    	if(lemma == null)
	    		lemma = lemma(d.tag(), d.word());
	    	d.setLemma(lemma);
	    });
	    return graph;
	    
    }
}
	

