/*
 * Copyright 2013 Hannes Janetzek
 *
 * This file is part of the OpenScienceMap project (http://www.opensciencemap.org).
 *
 * This program is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.oscim.core;

// TODO
// - getter methods!
// - check indexPos < Short.Max
// - should make internals private, maybe

/**
 * The GeometryBuffer class holds temporary geometry data for processing.
 * Only One geometry type can be set at a time. Use 'clear()' to reset the
 * internal state.
 * <p>
 * 'points[]' holds interleaved x,y coordinates
 * <p>
 * 'index[]' is used to store number of points within a geometry and encode
 * multi-linestrings and (multi-)polygons.
 */
public class GeometryBuffer {

	private final static int GROW_INDICES = 64;
	private final static int GROW_POINTS = 512;

	/**
	 * The Enum GeometryType.
	 */
	public enum GeometryType {
		NONE(0),
		POINT(1),
		LINE(2),
		POLY(3),
		TRIS(4);

		private GeometryType(int type) {
			nativeInt = type;
		}

		public final int nativeInt;
	}

	/** The points. */
	public float[] points;

	/** The indexes. */
	public short[] index;

	/** The current index position. */
	public int indexPos;

	/** The current position in points array. */
	public int pointPos;

	/** The current geometry type. */
	public GeometryType type;

	private PointF mTmpPoint = new PointF();
	private int pointLimit;

	/**
	 * Instantiates a new geometry buffer.
	 * 
	 * @param points the points
	 * @param index the index
	 */
	public GeometryBuffer(float[] points, short[] index) {
		if (points == null)
			points = new float[GROW_POINTS];
		if (index == null)
			index = new short[GROW_INDICES];

		this.points = points;
		this.index = index;
		this.type = GeometryType.NONE;
		this.indexPos = 0;
		this.pointPos = 0;
		this.pointLimit = points.length - 2;
	}

	public PointF getPoint(int i) {
		mTmpPoint.x = points[(i << 1)];
		mTmpPoint.y = points[(i << 1) + 1];
		return mTmpPoint;
	}

	public int getNumPoints() {
		return pointPos >> 1;
	}

	/**
	 * Instantiates a new geometry buffer.
	 * 
	 * @param numPoints the num points
	 * @param numIndices the num indices
	 */
	public GeometryBuffer(int numPoints, int numIndices) {
		this(new float[numPoints * 2], new short[numIndices]);
	}

	// ---- API ----
	/**
	 * Reset buffer.
	 */
	public void clear() {
		index[0] = 0;
		indexPos = 0;
		pointPos = 0;
		type = GeometryType.NONE;
	}

	/**
	 * Adds a point with the coordinate x, y.
	 * 
	 * @param x the x ordinate
	 * @param y the y ordinate
	 */
	public GeometryBuffer addPoint(float x, float y) {
		if (pointPos > pointLimit)
			ensurePointSize((pointPos >> 1) + 1, true);

		points[pointPos++] = x;
		points[pointPos++] = y;

		index[indexPos] += 2;
		return this;
	}

	public boolean isPoly() {
		return type == GeometryType.POLY;
	}

	public boolean isLine() {
		return type == GeometryType.LINE;
	}

	public boolean isPoint() {
		return type == GeometryType.POINT;
	}

	/**
	 * Sets the point x,y at position pos.
	 * 
	 * @param pos the pos
	 * @param x the x ordinate
	 * @param y the y ordinate
	 */
	public void setPoint(int pos, float x, float y) {
		points[(pos << 1) + 0] = x;
		points[(pos << 1) + 1] = y;
	}

	/**
	 * Set geometry type for points.
	 */
	public void startPoints() {
		setOrCheckMode(GeometryType.POINT);
	}

	/**
	 * Start a new line. Sets geometry type for lines.
	 */
	public GeometryBuffer startLine() {
		setOrCheckMode(GeometryType.LINE);

		// ignore
		if (index[indexPos] > 0) {

			// start next
			if ((index[0] >= 0) && (++indexPos >= index.length))
				ensureIndexSize(indexPos, true);

			// initialize with zero points
			index[indexPos] = 0;
		}

		// set new end marker
		if (index.length > indexPos + 1)
			index[indexPos + 1] = -1;
		return this;
	}

	/**
	 * Start a new polygon. Sets geometry type for polygons.
	 */
	public GeometryBuffer startPolygon() {
		boolean start = (type == GeometryType.NONE);
		setOrCheckMode(GeometryType.POLY);

		if ((indexPos + 3) > index.length)
			ensureIndexSize(indexPos + 2, true);

		if (!start && index[indexPos] != 0) {
			// end polygon
			index[++indexPos] = 0;

			// next polygon start
			indexPos++;
		}

		// initialize with zero points
		index[indexPos] = 0;

		// set new end marker
		if (index.length > indexPos + 1)
			index[indexPos + 1] = -1;

		return this;
	}

	/**
	 * Starts a new polygon hole (inner ring).
	 */
	public void startHole() {
		checkMode(GeometryType.POLY);

		if ((indexPos + 2) > index.length)
			ensureIndexSize(indexPos + 1, true);

		// initialize with zero points
		index[++indexPos] = 0;

		// set new end marker
		if (index.length > indexPos + 1)
			index[indexPos + 1] = -1;
	}

	public GeometryBuffer translate(float dx, float dy) {
		for (int i = 0; i < pointPos; i += 2) {
			points[i] += dx;
			points[i + 1] += dy;
		}
		return this;
	}

	public GeometryBuffer scale(float scaleX, float scaleY) {
		for (int i = 0; i < pointPos; i += 2) {
			points[i] *= scaleX;
			points[i + 1] *= scaleY;
		}
		return this;
	}

	/**
	 * Ensure that 'points' array can hold the number of points.
	 * 
	 * @param size the number of points to hold
	 * @param copy the the current data when array is reallocated
	 * @return the float[] array holding current coordinates
	 */
	public float[] ensurePointSize(int size, boolean copy) {
		if (size * 2 < points.length)
			return points;

		size = size * 2 + GROW_POINTS;

		float[] newPoints = new float[size];
		if (copy)
			System.arraycopy(points, 0, newPoints, 0, points.length);

		points = newPoints;
		pointLimit = size - 2;

		return points;
	}

	/**
	 * Ensure index size.
	 * 
	 * @param size the size
	 * @param copy the copy
	 * @return the short[] array holding current index
	 */
	public short[] ensureIndexSize(int size, boolean copy) {
		if (size < index.length)
			return index;

		short[] newIndex = new short[size + GROW_INDICES];
		if (copy)
			System.arraycopy(index, 0, newIndex, 0, index.length);

		index = newIndex;

		return index;
	}

	private void setOrCheckMode(GeometryType m) {
		if (type == m)
			return;

		if (type != GeometryType.NONE)
			throw new IllegalArgumentException("not cleared " + m + "<>" + type);

		type = m;
	}

	private void checkMode(GeometryType m) {
		if (type != m)
			throw new IllegalArgumentException("not cleared " + m + "<>" + type);
	}

	public void addPoint(Point p) {
		addPoint((float) p.x, (float) p.y);
	}

	public String toString() {
		StringBuffer sb = new StringBuffer();
		int o = 0;
		for (int i = 0; i < index.length; i++) {
			if (index[i] < 0)
				break;
			if (index[i] == 0)
				continue;

			for (int j = 0; j < index[i]; j += 2) {
				sb.append('[')
				    .append(points[o + j])
				    .append(",")
				    .append(points[o + j + 1])
				    .append(']');
			}
			sb.append('\n');
			o += index[i];
		}
		return sb.toString();
	}

}
