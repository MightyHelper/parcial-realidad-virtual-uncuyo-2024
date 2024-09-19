import processing.core.PApplet
import processing.core.PVector
import kotlin.math.pow

private const val FRICTION = 0.999f
private val GRAVITY = PVector(0f, -0.001f)


open class PhysicsItem(var position: PVector, var velocity: PVector, var acceleration: PVector) {
	open fun timeStep(dt: Float) {
		velocity.plusAssign(acceleration)
		position.plusAssign(velocity * dt)
		velocity.timesAssign(FRICTION.pow(dt))
		acceleration.timesAssign(0f)
	}
}

open class PhysicsValue(var position: Float, var velocity: Float, var acceleration: Float) {
	open fun timeStep(dt: Float) {
		velocity += acceleration
		position += velocity * dt
		velocity *= FRICTION.pow(dt)
		acceleration *= 0f
	}
}


class Ship(position: PVector, velocity: PVector, acceleration: PVector, val size: PVector, val rotation: PhysicsValue) :
	PhysicsItem(position, velocity, acceleration) {
	override fun timeStep(dt: Float) {
		acceleration.plusAssign(GRAVITY)
		super.timeStep(dt)
		rotation.timeStep(dt)
	}

	fun rotateLeft(dt: Float) {
		rotation.acceleration = 0.0001f * dt
	}

	fun rotateRight(dt: Float) {
		rotation.acceleration = -0.0001f * dt
	}

	fun thrustUp(dt: Float) {
		acceleration.plusAssign(PVector(0f, 0.002f).rotate(rotation.position) * dt)
	}

}

enum class State {
	FLYING,
	LANDED,
	CRASHED
}

class App : PApplet() {
	val SIM_WIDTH = 50
	val MAX_HEIGHT = 250
	var heightMap = mutableListOf<Int>()
	var heightMapDerivative = listOf<Int>()
	var ship = Ship(
		PVector(-10f, MAX_HEIGHT * 2f),
		PVector(0f, 0f),
		PVector(0f, 0f),
		PVector(10f, 10f),
		PhysicsValue(0.5f, 0f, 0f)
	)
	var lastFrameTime = 0L
	var displayNormal: Int = color(255, 255, 255)
	var accentTint: Int = color(255, 0, 0)
	var debugTint: Int = color(0, 255, 0)
	val pressedKeys: MutableSet<Int> = mutableSetOf()
	var state: State = State.FLYING

	override fun settings() {
		size(800, 600)
	}

	fun translate(v: PVector) {
		translate(v.x, v.y)
	}

	override fun setup() {
		initMap()
		computeDeltaTime()
	}

	private fun initMap() {
		heightMap = (-SIM_WIDTH / 2 until SIM_WIDTH / 2).map {
			constrain(
				-(it * it / 2) + MAX_HEIGHT + floor((randomGaussian() - 0.5f) * 10),
				0,
				MAX_HEIGHT
			)
		}.toMutableList()
		// Ensure map is solvable
		val guaranteedLandable = (0 until SIM_WIDTH - 1).random()
		heightMap[guaranteedLandable - 1] = heightMap[guaranteedLandable]
		heightMap[guaranteedLandable + 1] = heightMap[guaranteedLandable]
		heightMapDerivative = heightMap.zipWithNext { a, b -> b - a }
	}

	fun strokeNormal() = stroke(displayNormal)
	fun fillNormal() = fill(displayNormal)
	fun strokeAccent() = stroke(accentTint)
	fun fillAccent() = fill(accentTint)
	fun strokeDebug() = stroke(debugTint)
	fun fillDebug() = fill(debugTint)

	fun ship() {
		pushMatrix()
		noFill()
		strokeNormal()
		translate(ship.position)
		rotate(ship.rotation.position)
		translate(ship.size * -0.5f)
		strokeNormal()
		ellipse(ship.size.x / 2, ship.size.y * 1.5f, 5f, 5f)
		rect(0f, 0f, ship.size.x, ship.size.y)
		if (' '.code in pressedKeys) {
			fillAccent()
			noStroke()
			triangle(
				0f, 0f,
				ship.size.x * 0.5f, -ship.size.y * (2 - sin(millis() / 100f)),
				ship.size.x, 0f
			)
		}
		popMatrix()
	}

	fun landscapeIdxToX(idx: Int): Float = 8 * (idx - SIM_WIDTH.toFloat() / 2)

	fun landscapeXToIdx(x: Float): Int = round((x / 8) + (SIM_WIDTH / 2))

	fun landscape() {
		noFill()
		beginShape()
		strokeNormal()
		for (i in 0 until SIM_WIDTH) {
			vertex(landscapeIdxToX(i), heightMap[i].toFloat())
		}
		endShape()
		strokeDebug()
		beginShape()
		for (i in 0 until SIM_WIDTH - 1) {
			vertex(landscapeIdxToX(i), heightMap[i].toFloat())
		}
		endShape()
		noStroke()
		for (i in -width / 2 until width / 2) {
			infoColor(isLandable(i.toFloat()), 25f)
			rect(i.toFloat(), 0f, 1f, height.toFloat())
		}
	}

	fun timeStep(dt: Float) {
		if (dt > 10000) return  // Too strange
		ship.timeStep(dt)
	}

	fun computeDeltaTime(): Float {
		return (-lastFrameTime + frameRateLastNanos.also { lastFrameTime = it }) / 1e7f
	}

	fun debug() {
		fill(255)
		stroke(255)
		text(ship.position.str(), 10f, 10f)
		text(ship.velocity.str(), 10f, 30f)
		text(ship.acceleration.str(), 10f, 50f)
		text(ship.rotation.position.toString(), 10f, 70f)
		infoColor(isTerrainOkForLanding())
		text("Terrain: " + isTerrainOkForLanding().toString(), 10f, 90f)
		infoColor(isRotationOkForLanding())
		text("Rotation: " + isRotationOkForLanding().toString(), 10f, 110f)
		infoColor(isVelocityOkForLanding())
		text("Velocity: " + isVelocityOkForLanding().toString(), 10f, 130f)

	}

	private fun infoColor(bool: Boolean, alpha: Float=255f) {
		if (bool) {
			fill(0f, 255f, 0f, alpha)
		} else {
			fill(255f, 0f, 0f, alpha)
		}
	}

	override fun keyPressed() {
		pressedKeys.add(keyCode)
	}

	override fun keyReleased() {
		pressedKeys.remove(keyCode)
	}

	fun segmentsIntersect(a: PVector, b: PVector, c: PVector, d: PVector): Boolean {
		val a1 = b.y - a.y
		val b1 = a.x - b.x
		val c1 = a1 * a.x + b1 * a.y
		val a2 = d.y - c.y
		val b2 = c.x - d.x
		val c2 = a2 * c.x + b2 * c.y
		val det = a1 * b2 - a2 * b1
		if (det == 0f) {
			return false
		}
		val x = (b2 * c1 - b1 * c2) / det
		val y = (a1 * c2 - a2 * c1) / det
		val isOnAB = (min(a.x, b.x) <= x && x <= max(a.x, b.x)) && (min(a.y, b.y) <= y && y <= max(a.y, b.y))
		val isOnCD = (min(c.x, d.x) <= x && x <= max(c.x, d.x)) && (min(c.y, d.y) <= y && y <= max(c.y, d.y))
		return isOnAB && isOnCD
	}

	fun testIntersect() {
		// Test if the ship intersects the landscape, considering the ship as a rectangle with rotation where the center of rotation is the center of the ship
		val shipPos = ship.position
		val shipSize = ship.size
		val shipRotation = ship.rotation.position
		val shipVertices = listOf(
			PVector(-shipSize.x / 2, -shipSize.y / 2),
			PVector(shipSize.x / 2, -shipSize.y / 2),
			PVector(shipSize.x / 2, shipSize.y / 2),
			PVector(-shipSize.x / 2, shipSize.y / 2),
			PVector(-shipSize.x / 2, -shipSize.y / 2),
		).map { it.rotate(shipRotation) + shipPos }
		val shipEdges = shipVertices.zipWithNext { a, b -> Pair(a, b) }
		val landscapeEdges = (0 until SIM_WIDTH - 1).map {
			Pair(
				PVector(landscapeIdxToX(it), heightMap[it].toFloat()),
				PVector(landscapeIdxToX(it + 1), heightMap[it + 1].toFloat())
			)
		}
		(1 until SIM_WIDTH - 1).forEach {
			if (isLandable(landscapeIdxToX(it)) && isLandable(landscapeIdxToX(it - 1))) {
				fillDebug()
				ellipse(landscapeIdxToX(it), heightMap[it].toFloat(), 5f, 5f)
			}
		}
		val intersect = shipEdges.any { shipEdge ->
			landscapeEdges.any { landscapeEdge ->
				segmentsIntersect(shipEdge.first, shipEdge.second, landscapeEdge.first, landscapeEdge.second)
			}
		}
		if (intersect) {
			calculateIfCrash()
		}
	}

	fun calculateIfCrash() {
		// We are intersecting the terrain, if our velocity is too high or we are landing on uneven terrain, or we are not facing up, we crash
		val velocityOk = isVelocityOkForLanding()
		val landingOk = isRotationOkForLanding()
		val landingOnEvenTerrain = isTerrainOkForLanding()
		if (velocityOk && landingOk && landingOnEvenTerrain) {
			if (state == State.FLYING) {
				state = State.LANDED
				println("Landed!")
			}
		} else {
			if (state == State.FLYING) {
				state = State.CRASHED
				println("Crashed because velocityOk: $velocityOk, landingOk: $landingOk, landingOnEvenTerrain: $landingOnEvenTerrain")
			}
		}
	}

	private fun isTerrainOkForLanding(): Boolean =
		(floor(ship.position.x - ship.size.x / 2)..floor(ship.position.x + ship.size.x / 2)).all {
			isLandable(it.toFloat())
		}

	private fun isLandable(x: Float): Boolean = try {
		abs(heightMapDerivative[landscapeXToIdx(x)]) < 2
	} catch (_: IndexOutOfBoundsException) {
		false
	}

	private fun isRotationOkForLanding(): Boolean = abs(ship.rotation.position % (2 * PI)) <= 0.1f

	private fun isVelocityOkForLanding(): Boolean = ship.velocity.mag() < 0.05f

	fun renderUi() {
		debug()
		when (state) {
			State.FLYING -> {
				testIntersect()
				val dt = computeDeltaTime()
				handleKeys(dt)
				timeStep(dt)
			}

			State.LANDED -> {
				pushStyle()
				fill(0f, 255f, 0f)
				textSize(32f)
				textAlign(CENTER)
				text("LANDED", width / 2f, height / 2f)
				popStyle()
			}

			State.CRASHED -> {
				pushStyle()
				fill(255f, 0f, 0f)
				textSize(32f)
				textAlign(CENTER)
				text("CRASHED", width / 2f, height / 2f)
				popStyle()
			}
		}

	}

	override fun draw() {
		background(0)
		renderUi()
		translate(width.toFloat() / 2, (height - 10).toFloat())
		scale(1f, -1f)
		when (state) {
			State.FLYING -> {
				testIntersect()
				val dt = computeDeltaTime()
				handleKeys(dt)
				timeStep(dt)
			}

			else -> {}
		}

		ship()
		landscape()
	}

	private fun handleKeys(dt: Float) {
		if (LEFT in pressedKeys) ship.rotateLeft(dt)
		if (RIGHT in pressedKeys) ship.rotateRight(dt)
		if (' '.code in pressedKeys) ship.thrustUp(dt)
	}
}

private fun PVector.str(): String {
	return "$x, $y"
}

operator fun PVector.plusAssign(other: PVector) {
	this.add(other)
}

operator fun PVector.plus(other: PVector): PVector {
	return this.copy().add(other)
}

operator fun PVector.minus(other: PVector): PVector {
	return this.copy().sub(other)
}

operator fun PVector.timesAssign(other: Float) {
	this.mult(other)
}

operator fun PVector.times(other: Float): PVector {
	return this.copy().mult(other)
}

operator fun PVector.unaryMinus(): PVector {
	return this.copy() * -1f
}

fun main() {
	PApplet.main(App::class.java)
}
