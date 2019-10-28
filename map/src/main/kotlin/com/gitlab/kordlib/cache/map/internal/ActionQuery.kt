package com.gitlab.kordlib.cache.map.internal

import com.gitlab.kordlib.cache.api.DataCache
import com.gitlab.kordlib.cache.api.data.DataDescription
import com.gitlab.kordlib.cache.api.find
import com.gitlab.kordlib.cache.api.query.Query
import com.gitlab.kordlib.cache.map.MapLikeCollection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlin.reflect.typeOf

@ExperimentalCoroutinesApi
internal class ActionQuery<KEY, VALUE : Any>(
        private val cache: DataCache,
        private val collection: MapLikeCollection<KEY, VALUE>,
        private val description: DataDescription<VALUE, KEY>,
        private val actions: List<Action<KEY, VALUE>>
) : Query<VALUE> {
    override fun asFlow(): Flow<VALUE> = when {
        actions.isEmpty() -> collection.values()
        else -> actions.drop(1).fold(actions.first().onMap(collection)) { acc, action ->
            acc.filter { action.filter(description, it) }
        }
    }

    override suspend fun remove() = when {
        actions.isEmpty() -> {
            collection.clear()
            description.links.forEach {
                cache.getEntry<Any>(it.target)?.query()?.apply {
                    it.linkedField ne null
                }?.build()?.remove()
            }
        }
        else -> {
            asFlow().collect {
                collection.remove(description.indexField.property.get(it))
                description.links.forEach { link ->
                    cache.getEntry<Any>(link.target)?.query()?.apply {
                        link.linkedField eq link.source.get(it)
                    }?.build()?.remove()
                }
            }
        }
    }

    override suspend fun update(mapper: suspend (VALUE) -> VALUE) {
        asFlow().map {
            val prevId = description.indexField.property.get(it)
            val prev = it
            val new = mapper(it)
            val newId = description.indexField.property.get(new)

            if (newId != prevId) error("identity rule violated: $prevId -> $newId")
            if (prev != new) {
                collection.put(newId, new)
            }
        }.collect()
    }
}