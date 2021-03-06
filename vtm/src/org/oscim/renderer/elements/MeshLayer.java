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
package org.oscim.renderer.elements;

import java.nio.ShortBuffer;

import org.oscim.backend.GL20;
import org.oscim.backend.canvas.Color;
import org.oscim.core.GeometryBuffer;
import org.oscim.core.MercatorProjection;
import org.oscim.renderer.BufferObject;
import org.oscim.renderer.GLState;
import org.oscim.renderer.GLUtils;
import org.oscim.renderer.GLViewport;
import org.oscim.renderer.MapRenderer;
import org.oscim.theme.styles.AreaStyle;
import org.oscim.utils.Tessellator;
import org.oscim.utils.pool.Inlist;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MeshLayer extends RenderElement {
	static final Logger log = LoggerFactory.getLogger(MeshLayer.class);
	static final boolean dbg = false;

	BufferObject indicesVbo;
	int numIndices;

	VertexItem indiceItems;
	public AreaStyle area;
	public float heightOffset;

	public MeshLayer(int level) {
		super(RenderElement.MESH);
		this.level = level;
	}

	public void addMesh(GeometryBuffer geom) {
		if (geom.index[0] < 6)
			return;

		if (vertexItems == null) {
			vertexItems = VertexItem.pool.get();
			indiceItems = VertexItem.pool.get();
		}

		numIndices += Tessellator.tessellate(geom, MapRenderer.COORD_SCALE,
		                                     Inlist.last(vertexItems),
		                                     Inlist.last(indiceItems),
		                                     numVertices);

		numVertices = vertexItems.getSize() / 2;

		if (numIndices <= 0) {
			log.debug("empty " + geom.index);
			vertexItems = VertexItem.pool.releaseAll(vertexItems);
			indiceItems = VertexItem.pool.releaseAll(indiceItems);
		}
	}

	@Override
	protected void compile(ShortBuffer sbuf) {
		if (indiceItems == null) {
			indicesVbo = BufferObject.release(indicesVbo);
			return;
		}

		// add vertices to shared VBO
		ElementLayers.addPoolItems(this, sbuf);

		// add indices to indicesVbo
		sbuf = MapRenderer.getShortBuffer(numIndices);

		for (VertexItem it = indiceItems; it != null; it = it.next)
			sbuf.put(it.vertices, 0, it.used);

		indiceItems = VertexItem.pool.releaseAll(indiceItems);

		if (indicesVbo == null)
			indicesVbo = BufferObject.get(GL20.GL_ELEMENT_ARRAY_BUFFER, 0);

		indicesVbo.loadBufferData(sbuf.flip(), sbuf.limit() * 2);
	}

	@Override
	protected void clear() {
		indicesVbo = BufferObject.release(indicesVbo);
		indiceItems = VertexItem.pool.releaseAll(indiceItems);
		vertexItems = VertexItem.pool.releaseAll(vertexItems);
	}

	public static class Renderer {
		private static int shaderProgram;
		private static int hMatrix;
		private static int hColor;
		private static int hHeightOffset;
		private static int hVertexPosition;

		static boolean init() {
			shaderProgram = GLUtils.createProgram(vertexShader, fragmentShader);
			if (shaderProgram == 0)
				return false;

			hMatrix = GL.glGetUniformLocation(shaderProgram, "u_mvp");
			hColor = GL.glGetUniformLocation(shaderProgram, "u_color");
			hHeightOffset = GL.glGetUniformLocation(shaderProgram, "u_height");
			hVertexPosition = GL.glGetAttribLocation(shaderProgram, "a_pos");
			return true;
		}

		public static RenderElement draw(RenderElement l, GLViewport v) {

			GLState.blend(true);

			GLState.useProgram(shaderProgram);

			GLState.enableVertexArrays(hVertexPosition, -1);

			v.mvp.setAsUniform(hMatrix);

			float heightOffset = 0;
			GL.glUniform1f(hHeightOffset, heightOffset);

			for (; l != null && l.type == RenderElement.MESH; l = l.next) {
				MeshLayer ml = (MeshLayer) l;

				if (ml.indicesVbo == null)
					continue;

				if (ml.heightOffset != heightOffset) {
					heightOffset = ml.heightOffset;

					GL.glUniform1f(hHeightOffset, heightOffset /
					        MercatorProjection.groundResolution(v.pos));
				}

				ml.indicesVbo.bind();

				if (ml.area == null)
					GLUtils.setColor(hColor, Color.BLUE, 0.4f);
				else
					GLUtils.setColor(hColor, ml.area.color, 1);

				GL.glVertexAttribPointer(hVertexPosition, 2, GL20.GL_SHORT,
				                         false, 0, ml.offset);

				GL.glDrawElements(GL20.GL_TRIANGLES, ml.numIndices,
				                  GL20.GL_UNSIGNED_SHORT, 0);

				if (dbg) {
					GLUtils.setColor(hColor, Color.GRAY, 0.4f);
					GL.glDrawElements(GL20.GL_LINES, ml.numIndices,
					                  GL20.GL_UNSIGNED_SHORT, 0);
				}
			}

			GL.glBindBuffer(GL20.GL_ELEMENT_ARRAY_BUFFER, 0);

			return l;
		}

		private final static String vertexShader = ""
		        + "precision mediump float;"
		        + "uniform mat4 u_mvp;"
		        + "uniform float u_height;"
		        + "attribute vec2 a_pos;"
		        + "void main() {"
		        + "  gl_Position = u_mvp * vec4(a_pos, u_height, 1.0);"
		        + "}";

		private final static String fragmentShader = ""
		        + "precision mediump float;"
		        + "uniform vec4 u_color;"
		        + "void main() {"
		        + "  gl_FragColor = u_color;"
		        + "}";
	}
}
