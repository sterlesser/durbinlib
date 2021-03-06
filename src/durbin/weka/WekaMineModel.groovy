package durbin.weka;


/***
* Saves a trained classifier and any additional information needed to apply that classifier to a 
* new dataset.  I thought that the weka.classifier itself contained the instance names, but this seems
* to be true only for some classifiers, so this wrapper saves that and gives a central place to
* put any other model-specific info.   I'm toying with having the logic for using the model 
* here too... hmmm....
*/
class WekaMineModel implements Serializable{
	
	static {WekaAdditions.enable()}
	
	static err = System.err
	
	static final long serialVersionUID = 1L;
	
	def atrSelMethod  // Just for records sake

	def	discretization

	def classifier
	def attributes
	def className
	def classValues
	
	BootstrapNullModel bnm; 
		
	String toString(){
		def rval = "";
		rval += "AttributeSeletion: $atrSelMethod\n"
		rval += "Discretization: $discretization\n"
		rval += "Class Name: $className\n"
		rval += "ClassValues: ${classValues.join(";")}\n"				
		rval += "Classifier: $classifier\n"
		return(rval);
	}
	
	def attributes(){
		return(attributes);
	}
	
	def setNullModel(BootstrapNullModel nullmodel){
		bnm = nullmodel;
	}
	
			
	def WekaMineModel(instances,classifier){		
		this.classifier = classifier		
		this.discretization = "none"
		attributes = instances.attributeNames()	
		attributes = attributes - "ID" // Don't save ID if there is one. 
				
		className = instances.className()
		attributes = attributes - className	
		
		classValues = []
		def classAttr = instances.attribute(className)
		for(i in (0..<classAttr.numValues())){
			classValues.add(classAttr.value(i))
		}		
	}
	
	def WekaMineModel(instances,classifier,discretization){		
		this.classifier = classifier		
		this.discretization = discretization
		attributes = instances.attributeNames()	
		attributes = attributes - "ID" // don't save ID if there is one. 
		
		// Want to keep class attribute separate from attributes.
		className = instances.className()
		attributes = attributes - className	

		classValues = []
		def classAttr = instances.attribute(className)
		for(i in (0..<classAttr.numValues())){
			classValues.add(classAttr.value(i))
		}				
	}	
	
	def classAttribute(){
		return(className)
		//return(attributes[-1])
	}
	
	/****
	*
	*/ 
	def classify(instances){
		def rval = []
		def numInstances = instances.numInstances()		
		
		for(int i = 0;i < numInstances;i++){
			def instance = instances.instance(i)					
				
			//err.println "classifyInstance:"
			//def pred = classifier.classifyInstance(instance)
			//err.println "pred.class="+pred.class
			//err.println "pred: "+pred				
			def dist = classifier.distributionForInstance(instance)
			rval.add(dist)
		}
		return(rval)		
	}
	
	/***
	* Permutes the class lables and evaluates performance in a cross-validated setting. 
	* Accumulated values are saved in BootstrapNullModel for future significance computations. 
	**/ 
	def computeBootstrapNullModel(instances,nullModelIterations){						

		def rng = new Random();
		
		bnm = new BootstrapNullModel(classValues.size())
									
		// Generate the desired number of permutations of the model...
		for(i in 0..<nullModelIterations){
			err.print "Null model iteration $i..."
			//def pinstances = bnm.permuteClassLabels(instances)
			def pinstances = bnm.permuteAttributeValues(instances)
			
			// Apply classifier to these instances...
			// one result per instance, each result is a distribution for instance...
			// Note: it is assumed that instances have already been cleaned up. 
			def results = classify(pinstances)
			
			// Will need to have a null distribution for each class value... 						
			bnm.addPoints(results)
			err.println "done."
		}
	}
	
	
	def printResults(results,sampleIDs){
		printResults(Syste.out,results,sampleIDs)
	}
	
	def getMaxIdx(list){
		def maxVal = -9999999;
		def maxIdx = 0;
		list.eachWithIndex{val,i->
			if (val > maxVal) {
				maxVal = val
				maxIdx = i
			}
		}
		return(maxIdx)
	}
	
	/***
	*  Will break on multi-class classifiers. 
	*/ 
	def printResults(out,results,sampleIDs){
		if (bnm != null){
			out<<"ID,confidence1,confidence2,call,nullConfidence1,nullConfidence2\n"
		}else{
			out<<"ID,confidence1,confidence2,call\n"
		}
									
		results.eachWithIndex{result,i->
			def r = result as ArrayList	// distribution for instance...			
			def maxIdx = getMaxIdx(r)
			def call = classValues[maxIdx] // look up the name of this.
			def rstr = r.join(",")	
			
			if (bnm != null){
				def nullConf1 = bnm.getSignificance(r[0],0)						
				def nullConf2 = bnm.getSignificance(r[1],1)
				out<<"${sampleIDs[i]},$rstr,$call,$nullConf1,$nullConf2\n"
			}else{
				out<<"${sampleIDs[i]},$rstr,$call\n"
			}
		}		
	}
		
	/***
	* Print the results and compare with the clinical values in clinical...
	*/ 
	def printResultsAndCompare(results,dataSampleIDs,clinical){
		printResultsAndCompare(System.out,results,dataSampleIDs,clinical)
	}
	
	/***
	*	Evaluate the accuracy of the given results...
	*/
	def confusionMatrixFor(dataSampleIDs,results,clinical){
			// Build a map of clinical samples to results...
			def id2ClassMap = [:]						
			def classValues = clinical.attributeValues(clinical.classAttribute().name()) as ArrayList
			def classSet = classValues as Set
			classSet = classSet as ArrayList // convert the set back to an array so we can index it later...

			if (classSet.size() > 2){
				err.println "Sorry, but model comparison currently doesn't support more than two class values."
				err.println "This is planned for an update soon."
				err.println "ClassValues:"
				err.println classSet
				return;
			}

			def clinicalSampleIDs = clinical.attributeValues("ID") as ArrayList
			(0..classValues.size()).each{i->
				def id = clinicalSampleIDs[i]
				def classVal = classValues[i]
				id2ClassMap[id] = classVal
			}

			def tp = 0.0;
			def tn = 0.0;
			def fp = 0.0;
			def fn = 0.0;

			// Will break in a hurry if there are more than two...
			def val0 = classSet[0]
			def val1 = classSet[1]					

			confusionForSample = []
			
			// Now compare predictions with actual values...
			results.eachWithIndex{result,i->
				def r = result as ArrayList	// distribution for instance...			
				def maxIdx = getMaxIdx(r)
				def call = classValues[maxIdx] // look up the name of this.
				def rstr = r.join(",")	

				def id = dataSampleIDs[i]
				// If this sample is in the clinical data set, output it's comparison.. 
				if (id2ClassMap.containsKey(id)){
					def actualVal = id2ClassMap[id]
					if ((actualVal == val1) && (call == val1)){
						tp++;						
					}else if ((actualVal == val1) && (call == val0)){
						fp++
					}else if ((actualVal == val0) && (call == val0)){
						tn++;
					}else if ((actualVal == val0) && (call == val1)){
						fn++;
					}				
				}
			} // results.each
			return([tp,tn,fp,fn])
	}
		
	
	/***
	* results == distributionForInstance
	* dataSampleIDs == instanceIDs
	* clinical == clinical data
	* 
	*/ 
	def printResultsAndCompare(out,results,dataSampleIDs,clinical){
		out<< "ID,lowConfidence,highConfidence,call,actual\n"
		
		WekaAdditions.enable();
		
		// Build a map of clinical samples to results...
		def id2ClassMap = [:]						
		def classValues = clinical.attributeValues(clinical.classAttribute().name()) as ArrayList
		def classSet = classValues as Set
		classSet = classSet as ArrayList // convert the set back to an array so we can index it later...
		
		if (classSet.size() > 2){
			err.println "Sorry, but model comparison currently doesn't support more than two class values."
			err.println "This is planned for an update soon."
			err.println "ClassValues:"
			err.println classSet
			return;
		}
		
		
		def clinicalSampleIDs = clinical.attributeValues("ID") as ArrayList
		(0..classValues.size()).each{i->
			def id = clinicalSampleIDs[i]
			def classVal = classValues[i]
			id2ClassMap[id] = classVal
		}
		
		def tp = 0.0;
		def tn = 0.0;
		def fp = 0.0;
		def fn = 0.0;
							
		// Will break in a hurry if there are more than two...
		def val0 = classSet[0]
		def val1 = classSet[1]					
							
		// Now compare predictions with actual values...
		results.eachWithIndex{result,i->
			def r = result as ArrayList	// distribution for instance...			
			def maxIdx = getMaxIdx(r)
			def call = classValues[maxIdx] // look up the name of this.
			def rstr = r.join(",")	
			
			def id = dataSampleIDs[i]
			
		/*	
			def r = result as ArrayList	
			def rstr = r.join(",")

			
			def lowVal = r[0] as double
			def highVal = r[1] as double
			def call
			if (highVal > lowVal) call = "high"
			else call = "low"
			*/
			out<< "${id},$rstr,$call"
			
			// If this sample is in the clinical data set, output it's comparison.. 
			if (id2ClassMap.containsKey(id)){
				def actualVal = id2ClassMap[id]
				out<< ",$actualVal"
				
				// KJD Need to re-think the comparison stats for multi-class and user specified nominal
			//print "\t$lowVal,$highVal,${lowVal < highVal},${actualVal == 'low'}\t"
				if ((actualVal == val1) && (call == val1)){
					tp++;
					out<<",+\n"
				}else if ((actualVal == val1) && (call == val0)){
					fp++
					out<<"\n"					
				}else if ((actualVal == val0) && (call == val0)){
					tn++;
					out<<",+\n"
				}else if ((actualVal == val0) && (call == val1)){
					fn++;
					out<<"\n"
				}				
			}else{
				out<< ",?\n"
			}
		} // results.each				
		
	 	out<< "====================\n"
	  out<< "Positive: $val1\n"
	  out<< "Negative: $val0\n"
		out<< "TP\t$tp\n"
		out<< "FP\t$fp\n"
		out<< "TN\t$tn\n"
		out<< "FN\t$fn\n"
		out<< "Fraction Correct:\t${((tp+tn)/(tp+fp+tn+fn))}\n"
		out<<"Sensitivity:\t${(tp/(tp+fn))}\n"
		out<<"Specificity:\t${(tn/(tn+fp))}\n"										
		out<<"Precision:\t${(tp/(tp+fp))}\n"
		out<<"Recall:\t${(tp/(tp+fn))}\n"			
		def mcc = matthewsCorrelationCoeficient(tp,fp,tn,fn)					
		out<<"Matthews correlation coeficient: $mcc\n"
	
	}
	
	def matthewsCorrelationCoeficient(tp,fp,tn,fn){
		def sum1 = tp+fp
		def sum2 = tp+fn
		def sum3 = tn+fp
		def sum4 = tn+fn

		def denominator
		if ((sum1 == 0) || (sum2 == 0) || (sum3 ==0) || (sum4 ==0)){
			denominator = 1
		}else{	
			denominator = sum1*sum2*sum3*sum4
		}
		double mcc = ((tp*tn)-(fp*fn))/Math.sqrt(denominator)
		return(mcc.round(6))
	}
					
}
