/*
 * Copyright (c) 2009-2010, Peter Abeles. All Rights Reserved.
 *
 * This file is part of Efficient Java Matrix Library (EJML).
 *
 * EJML is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 *
 * EJML is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with EJML.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.ejml.alg.block.decomposition.qr;

import org.ejml.alg.block.BlockInnerMultiplication;
import org.ejml.data.D1Submatrix64F;

/**
 *
 * <p>
 * Contains various helper functions for performing a block matrix QR decomposition.
 * </p>
 *
 * <p>
 * Assumptions:
 * <ul>
 *  <le> All submatrices are aligned along the inner blocks of the {@link org.ejml.data.BlockMatrix64F}.
 * </ul>
 * 
 * @author Peter Abeles
 */
public class BlockHouseHolder {

    /**
     * Performs a standard QR decomposition on the specified submatrix that is one block wide.
     *
     * @param blockLength
     * @param Y
     * @param gamma
     */
    public static boolean decomposeQR_block_col( int blockLength ,
                                                 D1Submatrix64F Y ,
                                                 double gamma[] )
    {
        int width = Y.col1-Y.col0;
        for( int i = 0; i < width; i++ ) {
            // compute the householder vector
            if (!computeHouseHolder(blockLength, Y, gamma, i))
                return false;

            // apply to test of the columns in the block
            applyHouseholderCol(blockLength,Y,i,gamma[Y.col0+i]);
        }

        return true;
    }

    /**
     * <p>
     * Computes the householder vector that is used to create reflector for the column.
     * The results are stored in the original matrix.
     * </p>
     *
     * <p>
     * The householder vector 'u' is computed as follows:<br>
     * <br>
     * u(1) = 1 <br>
     * u(i) = x(i)/(&tau; + x(1))<br>
     * </p>
     *
     * The first element is implicitly assumed to be one and not written.
     *
     * @return If there was any problems or not. true = no problem.
     */
    private static boolean computeHouseHolder(int blockLength, D1Submatrix64F Y,
                                              double[] gamma, int i) {
        double max = BlockHouseHolder.findMaxCol(blockLength,Y,i);

        if( max == 0.0 ) {
            return false;
        } else {
            // computes tau and normalizes u by max
            double tau = computeTauAndDivide(blockLength, Y, i, max);

            // divide u by u_0
            double u_0 = Y.get(i,i) + tau;
            divideElements(blockLength,Y,i, u_0 );

            gamma[Y.col0+i] = u_0/tau;
            tau *= max;

            // after the reflector is applied the column would be all zeros but be -tau in the first element
            Y.set(i,i,-tau);
        }
        return true;
    }

    /**
     *
     * A = (I - &gamma;*u*u<sup>T</sup>)A
     */
    public static void applyHouseholderCol( int blockLength ,
                                            D1Submatrix64F A , int col , double gamma )
    {
        int width = A.col1 - A.col0;
        for( int j = col+1; j < width; j++ ) {

            double total = 0;

            // total = U^T * A
            for( int i = A.row0; i < A.row1; i += blockLength ) {

                int height = Math.min( blockLength , A.row1 - i );

                int indexU = i*A.original.numCols + height*A.col0 + col;
                int indexA = i*A.original.numCols + height*A.col0 + j;

                if( i == A.row0 ) {
                    indexU += width*(col+1);
                    indexA += width*col;

                    total = A.original.data[ indexA ];

                    indexA += width;

                    for( int k = col+1; k < height; k++ , indexU += width, indexA += width ) {
                        total += A.original.data[ indexU ] * A.original.data[ indexA ];
                    }
                } else {
                    for( int k = 0; k < height; k++ , indexU += width, indexA += width ) {
                        total += A.original.data[ indexU ] * A.original.data[ indexA ];
                    }
                }
            }

            total *= gamma;
            // A - gamma*u*total
            for( int i = A.row0; i < A.row1; i += blockLength ) {
                int height = Math.min( blockLength , A.row1 - i );

                int indexU = i*A.original.numCols + height*A.col0 + col;
                int indexA = i*A.original.numCols + height*A.col0 + j;

                if( i == A.row0 ) {
                    indexU += width*(col+1);
                    indexA += width*col;

                    A.original.data[ indexA ] -= total;

                    indexA += width;

                    for( int k = col+1; k < height; k++ , indexU += width, indexA += width ) {
                        A.original.data[ indexA ] -= total*A.original.data[ indexU ];
                    }
                } else {
                    for( int k = 0; k < height; k++ , indexU += width, indexA += width ) {
                        A.original.data[ indexA ] -= total*A.original.data[ indexU ];
                    }
                }
            }

        }
    }

    /**
     * Divides the elements at the specified column by 'val'.  Takes in account
     * the zeros and the first element being implicitly equal to one.
     */
    public static void divideElements( int blockLength ,
                                       D1Submatrix64F Y , int col , double val ) {
        int width = Y.col1-Y.col0;

        for( int i = Y.row0; i < Y.row1; i += blockLength ) {
            int height = Math.min( blockLength , Y.row1 - i );

            int index = i*Y.original.numCols + height*Y.col0 + col;

            if( i == Y.row0 ) {
                index += width*(col+1);

                for( int k = col+1; k < height; k++ , index += width ) {
                    Y.original.data[index] /= val;
                }
            } else {
                for( int k = 0; k < height; k++ , index += width ) {
                    Y.original.data[index] /= val;
                }
            }
        }
    }

    /**
     * <p>
     * From the specified column of Y tau is computed and each element is divided by 'max'.
     * See code below:
     * </p>
     *
     * <pre>
     * for i=col:Y.numRows
     *   Y[i][col] = u[i][col] / max
     *   tau = tau + u[i][col]*u[i][col]
     * end
     * tau = sqrt(tau)
     * if( Y[col][col] < 0 )
     *    tau = -tau;
     * </pre>
     *
     */
    public static double computeTauAndDivide( int blockLength ,
                                              D1Submatrix64F Y , int col , double max ) {
        int width = Y.col1-Y.col0;

        double top=0;
        double norm2 = 0;

        for( int i = Y.row0; i < Y.row1; i += blockLength ) {
            int height = Math.min( blockLength , Y.row1 - i );

            int index = i*Y.original.numCols + height*Y.col0 + col;

            if( i == Y.row0 ) {
                index += width*col;
                // save this value so that the sign can be determined later on
                top = Y.original.data[index] /= max;
                norm2 += top*top;
                index += width;

                for( int k = col+1; k < height; k++ , index += width ) {
                    double val = Y.original.data[index] /= max;
                    norm2 += val*val;
                }
            } else {
                for( int k = 0; k < height; k++ , index += width ) {
                    double val = Y.original.data[index] /= max;
                    norm2 += val*val;
                }
            }
        }

        norm2 = Math.sqrt(norm2);

        if( top < 0 )
            norm2 = -norm2;

        return norm2;
    }

    /**
     * Finds the element in the column with the largest absolute value. The offset
     * from zero is automatically taken in account based on the column.
     */
    public static double findMaxCol( int blockLength , D1Submatrix64F Y , int col )
    {
        int width = Y.col1-Y.col0;

        double max = 0;

        for( int i = Y.row0; i < Y.row1; i += blockLength ) {
            int height = Math.min( blockLength , Y.row1 - i );

            int index = i*Y.original.numCols + height*Y.col0 + col;

            if( i == Y.row0 ) {
                index += width*col;
                for( int k = col; k < height; k++ , index += width ) {
                    double v = Math.abs(Y.original.data[index]);
                    if( v > max ) {
                        max = v;
                    }
                }
            } else {
                for( int k = 0; k < height; k++ , index += width ) {
                    double v = Math.abs(Y.original.data[index]);
                    if( v > max ) {
                        max = v;
                    }
                }
            }
        }

        return max;
    }

    /**
     * <p>
     * Computes W from the householder reflectors stored in the columns of the column block
     * submatrix Y.
     * </p>
     *
     * <p>
     * Y = v<sup>(1)</sup><br>
     * W = -&beta;<sub>1</sub>v<sup>(1)</sup><br>
     * for j=2:r<br>
     * &nbsp;&nbsp;z = -&beta;(I +WY<sup>T</sup>)v<sup>(j)</sup> <br>
     * &nbsp;&nbsp;W = [W z]<br>
     * &nbsp;&nbsp;Y = [Y v<sup>(j)</sup>]<br>
     * end<br>
     * <br>
     * where v<sup>(.)</sup> are the house holder vectors, and r is the block length.  Note that
     * Y already contains the householder vectors so it does not need to be modified.
     * </p>
     *
     * <p>
     * Y and W are assumed to have the same number of rows and columns.
     * </p>
     *
     * @param Y Input matrix containing householder vectors.  Not modified.
     * @param W Resulting W matrix. Modified.
     * @param temp Used internally.  Must have W.numCols elements.
     * @param beta Beta's for householder vectors.
     * @param betaIndex Index of first relevant beta.
     */
    public static void computeW_Column( int blockLength ,
                                        D1Submatrix64F Y , D1Submatrix64F W ,
                                        double temp[], double beta[] , int betaIndex ) {

        int widthB = W.col1-W.col0;
        double b = beta[betaIndex++];

        // set the first column in W
        initializeW(blockLength, W, Y, widthB, b);

        // set up rest of the columns
        for( int j = 1; j < widthB; j++ ) {
            b = beta[betaIndex++];

            //compute the z vector and insert it into W
            computeY_t_V(blockLength,Y,j,temp);
            computeZ(blockLength,Y,W,j,temp,b);
        }
    }

    /**
     * <p>
     * Sets W to its initial value using the first column of 'y' and the value of 'b':
     * <br>
     * W = -&beta;v<br>
     * <br>
     * where v = Y(:,0).
     * </p>
     *
     * @param blockLength size of the inner block
     * @param W Submatrix being initialized.
     * @param Y Contains householder vector
     * @param widthB How wide the W block matrix is.
     * @param b beta
     */
    public static void initializeW(int blockLength,
                                    D1Submatrix64F W, D1Submatrix64F Y,
                                    int widthB, double b) {
        for( int i = W.row0; i < W.row1; i += blockLength ) {
            int heightW = Math.min( blockLength , W.row1 - i );

            int indexW = i*W.original.numCols + heightW*W.col0;
            int indexY = i*Y.original.numCols + heightW*Y.col0;

            // take in account the first element in V being 1
            if( i == W.row0 ) {
                W.original.data[indexW] = -b;
                indexW += widthB;
                indexY += widthB;
                for( int k = 1; k < heightW; k++ , indexW += widthB , indexY += widthB ) {
                    W.original.data[indexW] = -b* Y.original.data[indexY];
                }
            } else {
                for( int k = 0; k < heightW; k++ , indexW += widthB , indexY += widthB ) {
                    W.original.data[indexW] = -b* Y.original.data[indexY];
                }
            }
        }
    }

    /**
     * Computes the vector z and inserts it into 'W':<br>
     * <br>
     * z = - &beta;<sub>j</sub>*(V<sup>j</sup> + W*h)<br>
     * <br>
     * where h is a vector of length 'col' and was computed using {@link #computeY_t_V}.
     * V is a column in the Y matrix. Z is a column in the W matrix.  Both Z and V are
     * column 'col'.
     */
    public static void computeZ( int blockLength , D1Submatrix64F Y , D1Submatrix64F W,
                                 int col , double []temp , double beta )
    {
        int width = Y.col1-Y.col0;

        for( int i = Y.row0; i < Y.row1; i += blockLength ) {
            int heightW = Math.min( blockLength , Y.row1 - i );

            int indexW = i*W.original.numCols + heightW*W.col0;
            int indexZ = i*W.original.numCols + heightW*W.col0 + col;
            int indexV = i*Y.original.numCols + heightW*Y.col0 + col;


            for( int k = 0; k < heightW; k++ , indexZ += width, indexW += width , indexV += width ) {
                // compute the rows of W * h
                double total = 0;

                for( int j = 0; j < col; j++ ) {
                    total += W.original.data[indexW+j] * temp[j];
                }

                // add the two vectors together and multiply by -beta
                if( i == Y.row0 ) {
                    if( k < col ) {
                        W.original.data[indexZ] = -beta*total;
                    } else if( k == col ) {
                        W.original.data[indexZ] = -beta*(1.0 + total);
                    } else {
                        W.original.data[indexZ] = -beta*(Y.original.data[indexV] + total);
                    }
                } else {
                    double a = W.original.data[indexZ];
                    a = Y.original.data[indexV];

                    W.original.data[indexZ] = -beta*(Y.original.data[indexV] + total);
                }
            }
        }
    }

    /**
     * Computes Y<sup>T</sup>v<sup>(j)</sup>.  Where Y are the columns before 'col' and v is the column
     * at 'col'.  The zeros and ones are taken in account.  The solution is a vector with 'col' elements.
     *
     * width of Y must be along the block of original matrix A
     *
     * @param temp Temporary storage of least length 'col' 
     */
    public static void computeY_t_V( int blockLength , D1Submatrix64F Y , int col , double []temp )
    {
        int widthB = Y.col1-Y.col0;

        for( int j = 0; j < col; j++ ) {
            double total = 0;

            // multiply each column in Y by V
            // V is the column in at 'col'
            for( int i = Y.row0; i < Y.row1; i += blockLength ) {
                int heightW = Math.min( blockLength , Y.row1 - i );

                int indexY = i*Y.original.numCols + heightW*Y.col0 + j;
                int indexV = i*Y.original.numCols + heightW*Y.col0 + col;

                if( i == Y.row0 ) {
                    // skip zeros
                    indexY += widthB*col;
                    indexV += widthB*col;

                    // the first element in v is going to be 1
                    total = Y.original.data[indexY];

                    indexY += widthB;
                    indexV += widthB;

                    for( int k = col+1; k < heightW; k++ , indexV += widthB , indexY += widthB ) {
                        total += Y.original.data[indexY] * Y.original.data[indexV];
                    }
                } else {
                    for( int k = 0; k < heightW; k++ , indexV += widthB , indexY += widthB ) {
                        total += Y.original.data[indexY] * Y.original.data[indexV];
                    }
                }
            }

            temp[j] = total;
        }
    }

    /**
     * Special multiplication that takes in account the zeros and one in Y, which
     * is the matrix that stores the householder vectors.
     *
     */
    public static void multAdd_zeros( int blockLength ,
                                      D1Submatrix64F Y , D1Submatrix64F B ,
                                      D1Submatrix64F C )
    {
        int widthY = Y.col1 - Y.col0;

        for( int i = Y.row0; i < Y.row1; i += blockLength ) {
            int heightY = Math.min( blockLength , Y.row1 - i );

            for( int j = B.col0; j < B.col1; j += blockLength ) {
                int widthB = Math.min( blockLength , B.col1 - j );

                int indexC = (i-Y.row0+C.row0)*C.original.numCols + (j-B.col0+C.col0)*heightY;

                for( int k = Y.col0; k < Y.col1; k += blockLength ) {
                    int indexY = i*Y.original.numCols + k*heightY;
                    int indexB = (k-Y.col0+B.row0)*B.original.numCols + j*widthY;

                    if( i == Y.row0 ) {
                        multBlockAdd_zerosone(Y.original.data,B.original.data,C.original.data,
                            indexY,indexB,indexC,heightY,widthY,widthB);
                    } else {
                        BlockInnerMultiplication.multBlockAdd(Y.original.data,B.original.data,C.original.data,
                                indexY,indexB,indexC,heightY,widthY,widthB);
                    }
                }
            }
        }
    }

    /**
     * Inner block mult add operation that takes in account the zeros and on in dataA,
     * which is the top part of the Y block vector that has the householder vectors.
     */
    public static void multBlockAdd_zerosone( double[] dataA, double []dataB, double []dataC,
                                              int indexA, int indexB, int indexC,
                                              final int heightA, final int widthA, final int widthC) {
        for( int i = 0; i < heightA; i++ ) {
            for( int j = 0; j < widthC; j++ ) {
                double val = dataB[i*widthC+j+indexB];

                for( int k = 0; k < i; k++ ) {
                    val += dataA[i*widthA + k + indexA] * dataB[k*widthC + j + indexB];
                }

                dataC[ i*widthC + j + indexC ] += val;
            }
        }
    }

    /**
     * Performs a matrix multiplication on the block aligned submatrices.  A is
     * assumed to be lower triangular with diagonal elements set to 1.<br>
     * <br>
     * C = A^T * B
     */
    public static void multTransA( int blockLength ,
                                   D1Submatrix64F A , D1Submatrix64F B ,
                                   D1Submatrix64F C )
    {
        int widthA = A.col1 - A.col0;
        if( widthA > blockLength )
            throw new IllegalArgumentException("A is expected to be at most one block wide.");

        for( int j = B.col0; j < B.col1; j += blockLength ) {
            int widthB = Math.min( blockLength , B.col1 - j );

            int indexC = C.row0*C.original.numCols + (j-B.col0+C.col0)*widthA;

            for( int k = A.row0; k < A.row1; k += blockLength ) {
                int heightA = Math.min( blockLength , A.row1 - k );

                int indexA = k*A.original.numCols + A.col0*heightA;
                int indexB = (k-A.row0+B.row0)*B.original.numCols + j*heightA;

                if( k == A.row0 )
                    multTransABlockSet(A.original.data,B.original.data,C.original.data,
                            indexA,indexB,indexC,heightA,widthA,widthB);
                else
                    BlockInnerMultiplication.multTransABlockAdd(A.original.data,B.original.data,C.original.data,
                            indexA,indexB,indexC,heightA,widthA,widthB);
            }
        }
    }

    /**
     * Performs a matrix multiplication on an single inner block where A is assumed to be lower triangular with diagonal
     * elements equal to 1.<br>
     * <br>
     * C = A^T * B
     */
    protected static void multTransABlockSet( double[] dataA, double []dataB, double []dataC,
                                              int indexA, int indexB, int indexC,
                                              final int heightA, final int widthA, final int widthC) {
        for( int i = 0; i < widthA; i++ ) {
            for( int j = 0; j < widthC; j++ ) {
                double val = dataB[i*widthC + j + indexB];

                for( int k = i+1; k < heightA; k++ ) {
                    val += dataA[k*widthA + i + indexA] * dataB[k*widthC + j + indexB];
                }

                dataC[ i*widthC + j + indexC ] = val;
            }
        }
    }
}
