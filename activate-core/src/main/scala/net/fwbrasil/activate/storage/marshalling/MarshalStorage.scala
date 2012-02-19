package net.fwbrasil.activate.storage.marshalling

import net.fwbrasil.activate.entity.{ EntityValue, Var, Entity }
import net.fwbrasil.activate.storage.Storage
import net.fwbrasil.activate.util.CollectionUtil.toTuple
import net.fwbrasil.activate.util.RichList._
import scala.collection.mutable.{ Map => MutableMap }
import net.fwbrasil.activate.entity.EntityInstanceEntityValue
import net.fwbrasil.activate.query.Query
import net.fwbrasil.activate.storage.marshalling.Marshaller.marshalling
import net.fwbrasil.activate.storage.marshalling.Marshaller.unmarshalling

trait MarshalStorage extends Storage {

	override def toStorage(assignments: Map[Var[Any], EntityValue[Any]], deletes: Map[Entity, Map[Var[Any], EntityValue[Any]]]): Unit = {

		import Marshaller._

		val insertMap = MutableMap[Entity, MutableMap[String, StorageValue]]()
		val updateMap = MutableMap[Entity, MutableMap[String, StorageValue]]()
		val deleteMap = MutableMap[Entity, MutableMap[String, StorageValue]]()

		def propertyMap(map: MutableMap[Entity, MutableMap[String, StorageValue]], entity: Entity) =
			map.getOrElseUpdate(entity, newPropertyMap(entity))

		for ((entity, properties) <- deletes)
			propertyMap(deleteMap, entity) ++=
				(for ((ref, value) <- properties) yield (ref.name -> marshalling(value)))

		for ((ref, value) <- assignments) {
			val entity = ref.outerEntity
			val propertyName = ref.name
			if (!deletes.contains(entity))
				if (!entity.isPersisted)
					propertyMap(insertMap, entity) += (propertyName -> marshalling(value))
				else
					propertyMap(updateMap, entity) += (propertyName -> marshalling(value))
		}

		store(insertMap.mapValues(_.toMap).toMap, updateMap.mapValues(_.toMap).toMap, deleteMap.mapValues(_.toMap).toMap)
	}

	private[this] def newPropertyMap(entity: Entity) =
		MutableMap("id" -> (ReferenceStorageValue(Option(entity.id))).asInstanceOf[StorageValue])

	override def fromStorage(queryInstance: Query[_]): List[List[EntityValue[_]]] = {
		val entityValues =
			for (value <- queryInstance.select.values)
				yield value.entityValue
		val expectedTypes =
			(for (value <- entityValues)
				yield marshalling(value)).toList
		val result = query(queryInstance, expectedTypes)
		(for (line <- result)
			yield (for (i <- 0 until line.size)
			yield unmarshalling(line(i), entityValues(i))).toList)
	}

	def store(insertMap: Map[Entity, Map[String, StorageValue]], updateMap: Map[Entity, Map[String, StorageValue]], deleteSet: Map[Entity, Map[String, StorageValue]]): Unit

	def query(query: Query[_], expectedTypes: List[StorageValue]): List[List[StorageValue]]

}