/*
 * Copyright (c) 2020 Noonmaru
 *
 * Licensed under the General Public License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/gpl-2.0.php
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.github.noonmaru.tap.fake

import com.github.noonmaru.tap.packet.EntityPacket
import com.github.noonmaru.tap.packet.sendPacket
import com.github.noonmaru.tap.packet.sendPacketAll
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.*
import kotlin.collections.HashSet

/**
 * @author Nemo
 */
abstract class FakeEntity internal constructor(private val entity: Entity) {

    internal lateinit var manager: FakeEntityManager

    var glowing
        get() = entity.isGlowing
        set(value) {
            entity.isGlowing = value
            updateMeta = true
            enqueue()
        }

    var invisible
        get() = entity.invisible
        set(value) {
            entity.invisible = value
            updateMeta = true
            enqueue()
        }

    val world: World
        get() = entity.world

    private var prevUpdateLocation: Location = entity.location

    private val prevLocation: Location = prevUpdateLocation.clone()

    private val location: Location = prevLocation.clone()

    val trackers = HashSet<Player>()

    private val ignores = Collections.newSetFromMap(WeakHashMap<Player, Boolean>())

    var trackingRange = 128.0

    private var updateLoc = false

    protected var updateMeta = false

    private var queued = false

    var valid = true
        private set

    protected fun enqueue() {
        if (queued)
            return

        queued = true
        manager.enqueue(this)
    }

    fun getLocation(): Location {
        return prevLocation.clone()
    }

    fun getToLocation(): Location {
        return location.clone()
    }

    fun setPosition(
        world: World = prevLocation.world,
        x: Double = prevLocation.x,
        y: Double = prevLocation.y,
        z: Double = prevLocation.z,
        yaw: Float = prevLocation.yaw,
        pitch: Float = prevLocation.pitch
    ) {
        location.run {
            this.world = world
            this.x = x
            this.y = y
            this.z = z
            this.yaw = yaw
            this.pitch = pitch
        }

        updateLoc = true
        enqueue()
    }

    fun setPosition(loc: Location) {
        loc.run {
            setPosition(world, x, y, z, yaw, pitch)
        }
    }

    fun move(
        moveX: Double = 0.0,
        moveY: Double = 0.0,
        moveZ: Double = 0.0,
        yaw: Float = prevLocation.yaw,
        pitch: Float = prevLocation.pitch
    ) {
        prevLocation.run {
            setPosition(prevLocation.world, x + moveX, y + moveY, z + moveZ, yaw, pitch)
        }
    }

    fun move(v: Vector, yaw: Float = prevLocation.yaw, pitch: Float = prevLocation.pitch) {
        move(v.x, v.y, v.z, yaw, pitch)
    }

    open fun onUpdate() {
        queued = false

        if (updateLoc) {
            updateLoc = false
            updateLocation()
        }
        if (updateMeta) {
            updateMeta = false
            updateMeta()
        }
    }

    private fun updateLocation() {
        val from = prevUpdateLocation
        val to = location

        val deltaX = from.x delta to.x
        val deltaY = from.y delta to.y
        val deltaZ = from.z delta to.z

        to.run {
            entity.setPositionAndRotation(world, x, y, z, yaw, pitch)
        }

        if (from.world == to.world && (deltaX < -32768L || deltaX > 32767L || deltaY < -32768L || deltaY > 32767L || deltaZ < -32768L || deltaZ > 32767L)) { //Relative
            prevUpdateLocation.run {
                world = to.world
                x += deltaX / 4096.0
                y += deltaY / 4096.0
                z += deltaZ / 4096.0
                yaw = to.yaw
                pitch = to.pitch
            }

            val yaw = to.yaw
            val pitch = to.pitch

            val packet = if (from.yaw == yaw && from.pitch == pitch)
                EntityPacket.relativeMove(entity.entityId, deltaX.toShort(), deltaY.toShort(), deltaZ.toShort(), false)
            else
                EntityPacket.relativeMoveAndLook(
                    entity.entityId,
                    deltaX.toShort(),
                    deltaY.toShort(),
                    deltaZ.toShort(),
                    yaw,
                    pitch, false
                )
            trackers.sendPacketAll(packet)

        } else {
            prevUpdateLocation.run {
                world = to.world
                x = to.x
                y = to.y
                z = to.z
                yaw = to.yaw
                pitch = to.pitch
            }

            val packet = EntityPacket.teleport(entity)

            trackers.sendPacketAll(packet)
        }

        prevLocation.apply {
            this.world = to.world
            this.x = to.x
            this.y = to.y
            this.z = to.z
            this.yaw = to.yaw
            this.pitch = to.pitch
        }
    }

    internal fun updateTrackers() {
        val box = trackingRange.let { r -> BoundingBox.of(prevLocation, r, r, r) }
        removeOutOfRangeTrackers(box.expand(16.0))

        val players = prevLocation.world.getNearbyEntities(box) { entity -> entity is Player && entity.isValid }

        for (player in players) {
            player as Player

            if (player !in ignores && trackers.add(player)) {
                spawnTo(player)
            }
        }
    }

    private fun removeOutOfRangeTrackers(box: BoundingBox) {
        trackers.removeIf { player ->
            if (!player.isValid || prevLocation.world != player.world || !box.overlaps(player.boundingBox)) {
                val packet = EntityPacket.destroy(intArrayOf(entity.entityId))
                player.sendPacket(packet)
                true
            } else
                false
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Entity> applyMetadata(applier: (entity: T) -> Unit) {
        applier.invoke(entity as T)
        updateMeta = true
        enqueue()
    }

    private fun updateMeta() {
        val packet = EntityPacket.metadata(entity)
        trackers.sendPacketAll(packet)
    }

    abstract fun spawnTo(player: Player)

    fun showTo(player: Player) {
        ignores.remove(player)
    }

    fun hideTo(player: Player) {
        if (ignores.add(player) && trackers.remove(player)) {
            val packet = EntityPacket.destroy(intArrayOf(entity.entityId))
            player.sendPacket(packet)
        }
    }

    fun canSeeTo(player: Player): Boolean {
        return player in ignores
    }

    fun remove() {
        valid = false
        val packet = EntityPacket.destroy(intArrayOf(entity.entityId))
        trackers.sendPacketAll(packet)
    }
}

infix fun Double.delta(to: Double): Long {
    return ((to - this) * 4096).toLong()
}