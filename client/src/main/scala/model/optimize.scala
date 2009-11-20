package edu.berkeley.cs.scads.model.parser

import org.apache.log4j.Logger
import scala.collection.mutable.HashMap

sealed abstract class OptimizerException extends Exception
object Unsatisfiable extends OptimizerException
case class UnboundedQuery(desc: String) extends OptimizerException

case class UnimplementedException(desc: String) extends Exception

sealed abstract class Index {
	val namespace: String
	val attributes: List[String]
}

case class PrimaryIndex(namespace: String, attributes: List[String]) extends Index
case class SecondaryIndex(namespace: String, attributes: List[String], targetNamespace: String) extends Index

/**
 * The optimizer takes in a BoundQuery and figures out how to satisfy it.
 * It will created indexes as needed.
 */
class Optimizer(spec: BoundSpec) {
	val logger = Logger.getLogger("scads.optimizer")
	val compiler = new ScalaCompiler
	buildClasses()

	def optimizedSpec: BoundSpec = {
		spec.orphanQueries.foreach(query => {
			logger.debug("Optimizing: " + query._1)
			query._2.plan = getPlan(query._2)
		})

		spec.entities.values.foreach((entity) => {
			entity.queries.foreach((query) => {
				logger.debug("Optimizing: " + query._1)
				query._2.plan = getPlan(query._2)
			})
		})
		spec
	}

	def getPlan(query: BoundQuery):QueryPlan = {
		try {
			val plan = optimize(query.fetchTree, query.range)
			logger.debug("plan: " + query.plan)
			plan
		}
		catch {
			case UnimplementedException(desc) => {
				logger.fatal("UnimplementedException: " + desc)
				null
			}
		}
	}

	def optimize(fetch: BoundFetch, range: BoundRange):EntityProvider = {
		fetch match {
			case BoundFetch(entity, Nil, Unsorted, BoundPointerJoin(rname, child)) => {
				Materialize(getClass(entity.name),
					PointerJoin(entity.namespace, List(AttributeCondition(rname)), ReadRandomPolicy, optimize(child, range))
				)
			}
			case BoundFetch(entity, Nil, Unsorted, BoundFixedTargetJoin(rname, cardinality, child)) => {
				val childPlan = optimize(child, range)
				val selectedIndex = selectOrCreateIndex(entity, List(rname))
				val joinLimit = IntegerField(cardinality)
				val tupleStream = selectedIndex match {
					case SecondaryIndex(ns, attrs, tns) => {
						SequentialDereferenceIndex(tns, ReadRandomPolicy,
							PrefixJoin(ns, child.entity.keys.map(k => AttributeCondition(k)), joinLimit, ReadRandomPolicy, childPlan)
						)
					}
					case PrimaryIndex(ns, attrs) => {
						PrefixJoin(ns, child.entity.keys.map(k => AttributeCondition(k)), joinLimit, ReadRandomPolicy, childPlan)
					}
				}
				Materialize(getClass(entity.name), tupleStream)
			}
			case BoundFetch(_, _, Sorted(attr, asc), f:FixedCardinalityJoin) => {
				Sort(List(attr), asc,
					optimize(BoundFetch(fetch.entity, fetch.predicates, Unsorted, fetch.join), range)
				)
			}
			case BoundFetch(_, predicates, _, f:FixedCardinalityJoin) => {
				Selection(extractEqualityMap(predicates),
					optimize(BoundFetch(fetch.entity, Nil, fetch.order, fetch.join), range)
				)
			}
			case BoundFetch(entity, predicates, ordering, BoundInfiniteTargetJoin(rname, child)) => {
				val childPlan = optimize(child, range)
				val selectedIndex = selectOrCreateIndex(entity, List(rname))
				val joinLimit = IntegerField(100)
				val tupleStream = selectedIndex match {
					case SecondaryIndex(ns, attrs, tns) => {
						SequentialDereferenceIndex(tns, ReadRandomPolicy,
							PrefixJoin(ns, child.entity.keys.map(k => AttributeCondition(k)), joinLimit, ReadRandomPolicy, childPlan)
						)
					}
					case PrimaryIndex(ns, attrs) => {
						PrefixJoin(ns, child.entity.keys.map(k => AttributeCondition(k)), joinLimit, ReadRandomPolicy, childPlan)
					}
				}
				Materialize(getClass(entity.name), tupleStream)
			}
			case BoundFetch(entity, predicates, ordering, NoJoin) => {
				/* Map attributes to the values they should equal. Error contradicting predicates are found */
				val equalityMap = extractEqualityMap(predicates)
				val equalityAttributes = equalityMap.keys.toList
				val selectedIndex = selectOrCreateIndex(entity, equalityAttributes)

				def createLookupNode(ns: String, attrs: List[String], equalityMap: HashMap[String, Field], versionType: Version): TupleProvider = {
					/* If the index is over more attributes than the equality we need to do a prefix match */
					if(attrs.size > equalityMap.size) {
						val prefix = CompositeField(attrs.slice(0, equalityMap.size).map(equalityMap):_*)
						PrefixGet(ns, prefix, IntegerField(100), ReadRandomPolicy)
					}
					else {
						new SingleGet(ns, CompositeField(attrs.map(equalityMap):_*), ReadRandomPolicy)
					}
				}

        val tupleStream = selectedIndex match {
          case PrimaryIndex(ns, attrs) => {
							createLookupNode(ns, attrs, equalityMap, new IntegerVersion)
          }
          case SecondaryIndex(ns, attrs, tns) => {
						new SequentialDereferenceIndex(tns, ReadRandomPolicy,
							createLookupNode(ns, attrs, equalityMap, Unversioned)
						)
					}
        }
				new Materialize(getClass(entity.name), tupleStream)
			}
		}
	}

	protected def selectOrCreateIndex(entity: BoundEntity, attributes: List[String]): Index = {
		/* Find candidate indexes by looking for prefix matches of attributes */
		val candidateIndexes = entity.indexes.filter((i) => {
			i.attributes.startsWith(attributes)
		})
		logger.debug("Identified candidate indexes: " + candidateIndexes)

		if(candidateIndexes.size == 0) {
			/* No index exists, so we must create one. */
			val idxName = "idx" + entity.name + attributes.mkString("", "_", "")
			val idxAttributes = attributes ++ (entity.keys -- attributes)
			val newIndex = new SecondaryIndex(idxName, idxAttributes, entity.namespace)
			logger.debug("Creating index on " + entity.name + " over attributes" + idxAttributes)
			entity.indexes.append(newIndex)
			newIndex
			}
		else {
			candidateIndexes(0)
		}
	}

	protected def extractEqualityMap(predicates: List[BoundPredicate]): HashMap[String, Field] = {
		val equalityAttributeFieldMap = new HashMap[String, Field]
		predicates.map(_.asInstanceOf[AttributeEqualityPredicate]).foreach((p) => { //Note: We only handle equality
			equalityAttributeFieldMap.get(p.attributeName) match {
				case Some(value) => {
					if(value == p.value)
						logger.warn("Redundant equality found")
					else
						throw Unsatisfiable
				}
				case None => equalityAttributeFieldMap.put(p.attributeName, p.value)
			}
		})
		equalityAttributeFieldMap
	}

	protected def buildClasses(): Unit = {
		val source = ScalaGen(spec)
		logger.debug("Creating Entity Placeholders")
		logger.debug(source)
		compiler.compile(source)
	}

	protected def ascending(direction: Direction): Boolean = direction match {
		case Ascending => true
		case Descending => false
	}

	def getClass(entityName:String) = {
    compiler.classLoader.loadClass(entityName).asInstanceOf[Class[edu.berkeley.cs.scads.model.Entity]]
  }
}
