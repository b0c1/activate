package net.fwbrasil.activate.index

import net.fwbrasil.activate.ActivateContext
import net.fwbrasil.activate.entity._
import scala.collection.immutable.HashSet
import scala.collection.mutable.HashMap
import grizzled.slf4j.Logging
import net.fwbrasil.radon.util.Lockable
import net.fwbrasil.scala.UnsafeLazy._
import scala.collection.mutable.ListBuffer

class PersistedIndex[E <: Entity: Manifest, P <: PersistedIndexEntry[K]: Manifest, K] private[index] (
    keyProducer: E => K, indexEntryProducer: K => P, context: ActivateContext, preloadEntries: Boolean)
        extends ActivateIndex[E, K](keyProducer, context)
        with Logging
        with Lockable {

    import context._

    private val index = new HashMap[K, String]()
    private val invertedIndex = new HashMap[String, String]()

    override protected def reload: Unit =
        if (preloadEntries) {
            val entries =
                transactional(transient) {
                    for (
                        entry <- all[P];
                        id <- entry.ids
                    ) yield {
                        (entry.key, id, entry)
                    }
                }
            doWithWriteLock {
                for ((key, entityId, entry) <- entries) {
                    invertedIndex.put(entityId, entry.id)
                    index.put(key, entry.id)
                }
            }
        }

    override protected def indexGet(key: K): Set[String] = {
        val entryId =
            doWithReadLock {
                index.get(key)
            }.getOrElse {
                doWithWriteLock {
                    index.getOrElseUpdate(key, indexEntryProducer(key).id)
                }
            }
        entry(entryId).ids
    }

    override protected def clearIndex = {
        index.clear
        invertedIndex.clear
    }

    private val updatingIndex = new ThreadLocal[Boolean] {
        override def initialValue = false
    }

    override protected def updateIndex(
        inserts: List[Entity],
        updates: List[Entity],
        deletes: List[Entity]) = {
        if (!updatingIndex.get && (inserts.nonEmpty || updates.nonEmpty || deletes.nonEmpty)) {
            doWithWriteLock {
                val idsDelete = ListBuffer[String]()
                val updatedEntries = ListBuffer[(K, String, String)]()
                updatingIndex.set(true)
                try transactional {
                    deleteEntities(deletes, idsDelete)
                    insertEntities(inserts, updatedEntries)
                    updateEntities(updates, idsDelete, updatedEntries)
                }
                finally updatingIndex.set(false)
                for (id <- idsDelete)
                    invertedIndex.remove(id)
                for ((key, entityId, entryId) <- updatedEntries) {
                    invertedIndex.put(entityId, entryId)
                    index.put(key, entryId)
                }
            }
        }
    }

    private def updateEntities(
        entities: List[Entity],
        idsDelete: ListBuffer[String],
        updatedEntries: ListBuffer[(K, String, String)]) = {
        for (entity <- entities) {
            val key = keyProducer(entity.asInstanceOf[E])
            val entryId = index.getOrElse(key, indexEntryProducer(key).id)
            val entityId = entity.id
            val currentEntryOption = invertedIndex.get(entityId)
            val needsUpdate =
                currentEntryOption
                    .map(_ != entryId)
                    .getOrElse(true)
            if (needsUpdate) {
                currentEntryOption.map { oldEntry =>
                    entry(oldEntry).ids -= entityId
                    idsDelete += entityId
                }
                entry(entryId).ids += entityId
                updatedEntries += ((key, entity.id, entryId))
            }
        }
    }

    private def insertEntities(
        entities: List[Entity],
        updatedEntries: ListBuffer[(K, String, String)]) = {
        for (entity <- entities) {
            val key = keyProducer(entity.asInstanceOf[E])
            val entryId = index.getOrElse(key, indexEntryProducer(key).id)
            entry(entryId).ids += entity.id
            updatedEntries += ((key, entity.id, entryId))
        }
    }

    private def deleteEntities(
        entities: List[Entity],
        idsDelete: ListBuffer[String]) =
        for (entity <- entities) {
            val id = entity.id
            invertedIndex.get(id).map {
                entryId =>
                    entry(entryId).ids -= id
            }
            idsDelete += id
        }

    private def entry(id: String) =
        byId[P](id).getOrElse(
            throw new IllegalStateException(
                "Invalid persisted index entry id. If you dind't do direct manipulation at the database, " +
                    "please fill a bug report with this exception."))

    override def toString =
        transactional(new Transaction()(context)) {
            (for ((key, entryId) <- index) yield {
                key + " -> " + entry(entryId).ids
            }).mkString(",")
        }

}

trait PersistedIndexEntry[K] extends Entity {
    def key: K
    var ids: HashSet[String]
}

trait PersistedIndexContext {
    this: ActivateContext =>

    type PersistedIndexEntry[K] = net.fwbrasil.activate.index.PersistedIndexEntry[K]

    protected class PersistedIndexProducer0[E <: Entity: Manifest](preloadEntries: Boolean) {
        def on[K](keyProducer: E => K) =
            new PersistedIndexProducer1[E, K](keyProducer, preloadEntries)
    }

    protected class PersistedIndexProducer1[E <: Entity: Manifest, K](keyProducer: E => K, preloadEntries: Boolean) {
        def using[P <: PersistedIndexEntry[K]: Manifest](indexEntityProducer: K => P) =
            new PersistedIndex[E, P, K](keyProducer, indexEntityProducer, PersistedIndexContext.this, preloadEntries)
    }

    protected def persistedIndex[E <: Entity: Manifest] = new PersistedIndexProducer0[E](preloadEntries = true)
    protected def persistedIndex[E <: Entity: Manifest](preloadEntries: Boolean) = new PersistedIndexProducer0[E](preloadEntries)

} 
