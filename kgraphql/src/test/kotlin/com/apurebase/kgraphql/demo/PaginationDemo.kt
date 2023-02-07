package com.apurebase.kgraphql.demo

import com.apurebase.kgraphql.KGraphQL
import kotlin.reflect.KClass

class Connection<T : Any, K : Any>(
    val totalCount: Int,
    val edges: List<Edge<T, K>>,
    val pageInfo: PageInfo<K>
) {
    class Edge<T : Any, K: Any>(
        val node: T,
        val cursor: K
    )

    class PageInfo<K: Any>(
        val endCursor: K,
        val hasNextPage: Boolean
    )
}

val names = listOf("Kai", "Eliana", "Jayden", "Ezra", "Luca", "Rowan", "Nova", "Amara")

data class Person<T>(
    val id: T
)

fun main() {
    val schema = KGraphQL.schema {
        configure {
            this.useDefaultPrettyPrinter = true
        }
        query("names") {
            resolver { ->
                Connection(
                    totalCount = names.size,
                    edges = names.subList(0, 2).mapIndexed { index, name ->
                        Connection.Edge(
                            node = name,
                            cursor = index
                        )
                    },
                    pageInfo = Connection.PageInfo(
                        endCursor = 1,
                        hasNextPage = true
                    )
                )
            }.returns<Connection<String, Int>>()
        }
        query("people") {
            resolver { ->
                Connection(
                    totalCount = names.size,
                    edges = names.subList(0, 2).mapIndexed { index, name ->
                        Connection.Edge(
                            node = Person(name),
                            cursor = index
                        )
                    },
                    pageInfo = Connection.PageInfo(
                        endCursor = 1,
                        hasNextPage = true
                    )
                )
            }.returns<Connection<Person<String>, Int>>()
        }
    }

    schema.types.forEach { println(it.name) }
    println()
    println(schema.executeBlocking("{ names { totalCount, edges { node, cursor }, pageInfo { endCursor, hasNextPage } } }"))
}