package com.github.linwancen.plugin.graph.printer

import com.github.linwancen.plugin.common.TaskTool
import com.github.linwancen.plugin.graph.parser.RelData
import com.github.linwancen.plugin.graph.settings.DrawGraphAppState
import com.google.gson.Gson
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.DriverManager
import java.sql.Types

class RelData2SQLite {
    private val log = LoggerFactory.getLogger(this::class.java)

    fun save(project: Project, relData: RelData) {
        object : Task.Backgroundable(project, "draw save sqlite") {
            override fun run(indicator: ProgressIndicator) {
                val path = DrawGraphAppState.of().tempPath
                try {
                    Class.forName("org.sqlite.JDBC")
                } catch (e: Throwable) {
                    log.warn("org.sqlite.JDBC not found")
                    return
                }
                try {
                    File(path).mkdirs()
                    DriverManager.getConnection("jdbc:sqlite:$path/draw_graph.sqlite").use { connection ->
                        connection.createStatement().use { s ->
                            s.execute("PRAGMA synchronous=OFF")
                            s.executeUpdate("drop table if exists itemMap")
                            s.executeUpdate("create table itemMap(sign TEXT, info TEXT)")
                            s.executeUpdate("drop table if exists parentChildMap")
                            s.executeUpdate("create table parentChildMap(parentSign TEXT, childSign TEXT)")
                            s.executeUpdate("drop table if exists callSet")
                            s.executeUpdate("create table callSet(usageSign TEXT, callSign TEXT)")
                            s.executeUpdate("drop table if exists callRelation")
                            s.executeUpdate(
                                "create table callRelation(callerSign TEXT, targetSign TEXT, sourcePath TEXT, sourceOffset INTEGER, sourceLine INTEGER, resolution TEXT, depth INTEGER)"
                            )
                        }
                        val parentSize = relData.parentChildMap.values.sumBy { it.size }
                        val taskTool = TaskTool(
                            indicator,
                            relData.itemMap.size + parentSize + relData.callSet.size + relData.callRelations.size,
                        )
                        var i = 0
                        val gson = Gson()
                        connection.autoCommit = false
                        connection.prepareStatement("insert into itemMap values(?, ?)").use { p ->
                            relData.itemMap.forEach {
                                p.setString(1, it.key)
                                p.setString(2, gson.toJson(it.value))
                                p.addBatch()
                                if (i++ % 1000 == 0) {
                                    taskTool.beforeNext(i, "1. insert into itemMap")
                                    p.executeBatch()
                                    connection.commit()
                                }
                            }
                            p.executeBatch()
                            connection.commit()
                        }
                        connection.prepareStatement("insert into parentChildMap values(?, ?)").use { p ->
                            relData.parentChildMap.forEach {
                                p.setString(1, it.key)
                                it.value.forEach { child ->
                                    p.setString(2, child)
                                    p.addBatch()
                                    if (i++ % 1000 == 0) {
                                        taskTool.beforeNext(i, "2. insert into parentChildMap)")
                                        p.executeBatch()
                                        connection.commit()
                                    }
                                }
                            }
                            p.executeBatch()
                            connection.commit()
                        }
                        connection.prepareStatement("insert into callSet values(?, ?)").use { p ->
                            relData.callSet.forEach {
                                p.setString(1, it.first)
                                p.setString(2, it.second)
                                p.addBatch()
                                if (i++ % 1000 == 0) {
                                    taskTool.beforeNext(i, "3. insert into callSet)")
                                    p.executeBatch()
                                    connection.commit()
                                }
                            }
                            p.executeBatch()
                            connection.commit()
                        }
                        connection.prepareStatement("insert into callRelation values(?, ?, ?, ?, ?, ?, ?)").use { p ->
                            relData.callRelations.forEach {
                                p.setString(1, it.callerSign)
                                p.setString(2, it.targetSign)
                                p.setString(3, it.callSite.sourcePath)
                                if (it.callSite.sourceOffset == null) {
                                    p.setNull(4, Types.INTEGER)
                                } else {
                                    p.setInt(4, it.callSite.sourceOffset)
                                }
                                if (it.callSite.sourceLine == null) {
                                    p.setNull(5, Types.INTEGER)
                                } else {
                                    p.setInt(5, it.callSite.sourceLine)
                                }
                                p.setString(6, it.callSite.resolution)
                                p.setInt(7, it.depth)
                                p.addBatch()
                                if (i++ % 1000 == 0) {
                                    taskTool.beforeNext(i, "4. insert into callRelation")
                                    p.executeBatch()
                                    connection.commit()
                                }
                            }
                            p.executeBatch()
                            connection.commit()
                        }
                    }
                } catch (e: Throwable) {
                    log.info("RelData2SQLite fail", e)
                }
            }
        }.queue()
    }
}