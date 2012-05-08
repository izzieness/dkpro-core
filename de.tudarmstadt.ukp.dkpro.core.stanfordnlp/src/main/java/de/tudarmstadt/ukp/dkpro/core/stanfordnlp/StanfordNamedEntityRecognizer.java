/*******************************************************************************
 * Copyright 2010
 * Ubiquitous Knowledge Processing (UKP) Lab
 * Technische Universität Darmstadt
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl-3.0.txt
 ******************************************************************************/
package de.tudarmstadt.ukp.dkpro.core.stanfordnlp;

import static org.apache.commons.io.IOUtils.closeQuietly;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.List;
import java.util.zip.GZIPInputStream;

import org.apache.uima.UimaContext;
import org.apache.uima.analysis_engine.AnalysisEngineProcessException;
import org.apache.uima.cas.CAS;
import org.apache.uima.cas.Type;
import org.apache.uima.jcas.JCas;
import org.apache.uima.resource.ResourceInitializationException;
import org.apache.uima.util.Level;
import org.uimafit.component.JCasAnnotator_ImplBase;
import org.uimafit.descriptor.ConfigurationParameter;

import de.tudarmstadt.ukp.dkpro.core.api.ner.type.NamedEntity;
import de.tudarmstadt.ukp.dkpro.core.api.parameter.ComponentParameters;
import de.tudarmstadt.ukp.dkpro.core.api.resources.MappingProvider;
import de.tudarmstadt.ukp.dkpro.core.api.resources.CasConfigurableProviderBase;
import edu.stanford.nlp.ie.AbstractSequenceClassifier;
import edu.stanford.nlp.ie.crf.CRFClassifier;
import edu.stanford.nlp.util.CoreMap;
import edu.stanford.nlp.util.Triple;

/**
 * This Annotator that uses the Stanford' implementation of the <br>
 * named entity recognizer.
 * <p>
 * Model files are not distributed as part of DKPro and need to be obtained from the Stanford NLP
 * tools homepage. Alternatively you can use the ANT script src/scripts/build.xml included with the
 * source code of this component, which downloads the models from the Stanford NLP homepage and
 * packages them conveniently as JARs which you may then install in your local Maven repository or
 * deploy to a private Maven repository.
 * 
 * @author Richard Eckart de Castilho
 * @author Oliver Ferschke
 */
public class StanfordNamedEntityRecognizer
	extends JCasAnnotator_ImplBase
{
	public static final String PARAM_LANGUAGE = ComponentParameters.PARAM_LANGUAGE;
	@ConfigurationParameter(name = PARAM_LANGUAGE, mandatory = false)
	protected String language;

	public static final String PARAM_VARIANT = "variant";
	@ConfigurationParameter(name = PARAM_VARIANT, mandatory = false)
	protected String variant;

	public static final String PARAM_MODEL_LOCATION = ComponentParameters.PARAM_MODEL_LOCATION;
	@ConfigurationParameter(name = PARAM_MODEL_LOCATION, mandatory = false)
	protected String modelLocation;

	public static final String PARAM_MAPPING_LOCATION = "mappingLocation";
	@ConfigurationParameter(name = PARAM_MAPPING_LOCATION, mandatory = false)
	protected String mappingLocation;

	private CasConfigurableProviderBase<AbstractSequenceClassifier<? extends CoreMap>> modelProvider;
	private MappingProvider mappingProvider;
	
	@Override
	public void initialize(UimaContext aContext)
		throws ResourceInitializationException
	{
		super.initialize(aContext);

		mappingProvider = new MappingProvider();
		mappingProvider.setDefaultVariantsLocation(
				"de/tudarmstadt/ukp/dkpro/core/stanfordnlp/lib/ner-default-variants.map");
		mappingProvider.setDefault(MappingProvider.LOCATION, "classpath:/de/tudarmstadt/ukp/dkpro/" +
				"core/stanfordnlp/lib/ner-${language}-${variant}.map");
		mappingProvider.setDefault(MappingProvider.BASE_TYPE, NamedEntity.class.getName());
		mappingProvider.setOverride(MappingProvider.LOCATION, mappingLocation);
		mappingProvider.setOverride(MappingProvider.LANGUAGE, language);
		mappingProvider.setOverride(MappingProvider.VARIANT, variant);
		
		modelProvider = new CasConfigurableProviderBase<AbstractSequenceClassifier<? extends CoreMap>>() {
			{
				setDefaultVariantsLocation(
						"de/tudarmstadt/ukp/dkpro/core/stanfordnlp/lib/ner-default-variants.map");
				setDefault(LOCATION, "classpath:/de/tudarmstadt/ukp/dkpro/core/stanfordnlp/lib/" +
						"ner-${language}-${variant}.ser.gz");
				setDefault(VARIANT, "maxent");
				
				setOverride(LOCATION, modelLocation);
				setOverride(LANGUAGE, language);
				setOverride(VARIANT, variant);
			}
			
			@Override
			protected AbstractSequenceClassifier<? extends CoreMap> produceResource(URL aUrl) throws IOException
			{
				InputStream is = null;
				try {
					is = aUrl.openStream();
					if (aUrl.toString().endsWith(".gz")) {
						// it's faster to do the buffering _outside_ the gzipping as here
						is = new GZIPInputStream(is);
					}
					
					@SuppressWarnings("unchecked")
					AbstractSequenceClassifier<? extends CoreMap> classifier = CRFClassifier.getClassifier(is);
					
					getContext().getLogger().log(Level.INFO, "Types: "+classifier.classIndex);
					
					return classifier;
				}
				catch (ClassNotFoundException e) {
					throw new IOException(e);
				}
				finally {
					closeQuietly(is);
				}
			}
		};
	}

	@Override
	public void process(JCas aJCas)
		throws AnalysisEngineProcessException
	{
		CAS cas = aJCas.getCas();
		modelProvider.configure(cas);
		mappingProvider.configure(cas);
		
		// get the document text
		String documentText = cas.getDocumentText();

		// test the string
		List<Triple<String, Integer, Integer>> namedEntities = modelProvider.getResource()
				.classifyToCharacterOffsets(documentText);

		// get the named entities and their character offsets
		for (Triple<String, Integer, Integer> namedEntity : namedEntities) {
			int begin = namedEntity.second();
			int end = namedEntity.third();

			Type type = mappingProvider.getTagType(namedEntity.first());
			NamedEntity neAnno = (NamedEntity) cas.createAnnotation(type, begin, end);
			neAnno.setValue(namedEntity.first());
			neAnno.addToIndexes();
		}
	}
}
