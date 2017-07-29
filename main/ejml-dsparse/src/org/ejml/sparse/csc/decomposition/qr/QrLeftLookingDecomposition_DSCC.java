/*
 * Copyright (c) 2009-2017, Peter Abeles. All Rights Reserved.
 *
 * This file is part of Efficient Java Matrix Library (EJML).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ejml.sparse.csc.decomposition.qr;

import org.ejml.data.DGrowArray;
import org.ejml.data.DMatrixSparseCSC;
import org.ejml.data.DScalar;
import org.ejml.data.IGrowArray;
import org.ejml.sparse.ComputePermutation;
import org.ejml.sparse.DecompositionSparseInterface;
import org.ejml.sparse.csc.CommonOps_DSCC;
import org.ejml.sparse.csc.mult.ImplSparseSparseMult_DSCC;

import java.util.Arrays;

/**
 * <p>Left-looking QRP decomposition algorithm for sparse matrices. A*P=Q*R</p>
 *
 * TODO Can only be applied to tall matrices right?!
 *
 * <p>NOTE: See qr_left on page 71 and cs_qr() in csparse </p>
 *
 * @author Peter Abeles
 */
// TODO if singular mark it as such
    // TODO is this really QRP?
public class QrLeftLookingDecomposition_DSCC implements
        QrpSparseDecomposition<DMatrixSparseCSC>, // TODO create a sparse QR interface?
        DecompositionSparseInterface<DMatrixSparseCSC>
{
    // shape of matrix and m2 includes fictitious rows
    int m,n,m2;
    ComputePermutation<DMatrixSparseCSC> p_reduceFill;
    // storage for permutation done to reduce the fill in
    IGrowArray gperm = new IGrowArray();
    IGrowArray ginvperm = new IGrowArray();
    // A matrix which has been permuted by gperm
    DMatrixSparseCSC Aperm = new DMatrixSparseCSC(1,1,0);

    // if fill in permtuation it will be Aperm if not then it will be A
    DMatrixSparseCSC C;

    // storage for Householder vectors
    DMatrixSparseCSC V = new DMatrixSparseCSC(1,1,0);
    // Storage for R matrix in QR
    DMatrixSparseCSC R = new DMatrixSparseCSC(1,1,0);
    // storage for beta in (I - beta*v*v')
    double beta[] = new double[0];
    DScalar Beta = new DScalar(); // used to get return value from a function

    // local workspace
    double x[] = new double[0];

    QrStructuralCounts_DSCC structure = new QrStructuralCounts_DSCC();
    int structureP[] = new int[0];
    IGrowArray gwork = new IGrowArray();
    DGrowArray gx = new DGrowArray();

    public QrLeftLookingDecomposition_DSCC(ComputePermutation<DMatrixSparseCSC> permutation ) {
        this.p_reduceFill = permutation;

        // use the same work space to reduce the overall memory foot print
        this.structure.setGwork(gwork);
    }

    @Override
    public boolean decompose(DMatrixSparseCSC A) {
        // If requested, apply fill in reducing permutation
        if( p_reduceFill != null ) {
            Aperm.reshape(A.numRows,Aperm.numCols,A.nz_length);
            p_reduceFill.process(A, gperm);
            ginvperm.reshape(gperm.length);
            CommonOps_DSCC.permutationInverse(gperm.data, ginvperm.data, gperm.length);
            CommonOps_DSCC.permuteSymmetric(A, ginvperm.data, Aperm, gwork);
            C = Aperm;
        } else {
            C = A;
        }

        // compute the structure of V and R
        structure.process(C);

        // Initialize data structured used in the decomposition
        initializeDecomposition(C);

        // perform the decomposution
        performDecomposition(C);

        return true;
    }

    private void performDecomposition(DMatrixSparseCSC A) {
        int w[] = gwork.data;
        int perm[] = gperm.data;
        int parent[] = structure.getParent();
        int leftmost[] = structure.getLeftMost();
        // permutation that was done to ensure all rows have non-zero elements
        int pinv_structure[] = structure.getPinv();
        int s = m2;

        // clear mark nodes. See addRowsInAInToC
        Arrays.fill(w,0,m2,-1);
        Arrays.fill(x,0,m2,0);

        // the counts from structure are actually an upper limit. the actual counts can be lower
        R.nz_length = 0;
        V.nz_length = 0;

        // compute V and R
        for (int k = 0; k < n; k++) {
            R.col_idx[k] = R.nz_length;
            int p1 = V.col_idx[k] = V.nz_length;
            w[k] = k;
            V.nz_rows[V.nz_length++] = k;                       // Add V(k,k) to V's pattern
            int top = n;
            int col = p_reduceFill != null ? perm[k] : k;

            int idx0 = A.col_idx[col];
            int idx1 = A.col_idx[col+1];

            for (int p = idx0; p <idx1 ; p++) {
                int i = leftmost[A.nz_rows[p]];
                int len;
                for (len = 0; w[i] != k ; i = parent[i]) {
                    w[s + len++] = i;
                    w[i] = k;
                }
                while( len > 0) {
                    w[s + --top] = w[s + --len];
                }
                i = pinv_structure[A.nz_rows[p]];
                x[i] = A.nz_values[p];
                if( i > k && w[i] < k) {
                    V.nz_rows[V.nz_length++] = i;
                    w[i] = k;
                }
            }
            // apply previously computed Householder vectors to the current columns
            for (int p = top; p < n; p++) {
                int i = w[s+p];
                QrHelperFunctions_DSCC.applyHouseholder(V,i,beta[i],x);
                R.nz_rows[R.nz_length] = i;
                R.nz_values[R.nz_length++] = x[i];
                x[i] = 0;
                if( parent[i] == k ) {
                    ImplSparseSparseMult_DSCC.addRowsInAInToC(V, i, V, k, w);
                }
            }
            for (int p = p1; p < V.nz_length; p++) {
                V.nz_values[p] = x[V.nz_rows[p]];
                x[V.nz_rows[p]] = 0;
            }
            R.nz_rows[R.nz_length] = k;
            R.nz_values[R.nz_length] = QrHelperFunctions_DSCC.computeHouseholder(V.nz_values,p1,V.nz_length,Beta);

            beta[k] = Beta.value;
            R.nz_length++;
        }
        R.col_idx[n] = R.nz_length;
        V.col_idx[n] = V.nz_length;
    }

    private void initializeDecomposition(DMatrixSparseCSC A ) {
        this.m2 = structure.getFicticousRowCount();
        this.m = A.numRows;
        this.n = A.numCols;

        if( beta.length < n ) {
            beta = new double[n];
        }
        if( x.length < m2 ) {
            x = new double[m2];
            structureP = new int[m2];
        }

        V.reshape(m2,n,structure.nz_in_V);
        R.reshape(m2,n,structure.nz_in_R);
    }

    @Override
    public int getRank() {
        return 0;
    }

    @Override
    public int[] getColPivots() {
        return new int[0];
    }

    @Override
    public DMatrixSparseCSC getColPivotMatrix(DMatrixSparseCSC P) {
        return CommonOps_DSCC.identity(V.numCols,V.numCols);
    }

    @Override
    public int[] getRowPivots() {
        return structure.pinv;
    }

    @Override
    public DMatrixSparseCSC getRowPivotMatrix(DMatrixSparseCSC P) {
        if( P == null )
            P = new DMatrixSparseCSC(1,1,0);
        P.reshape(V.numRows,V.numRows,V.numRows);
        int p[] = new int[V.numRows];
        CommonOps_DSCC.permutationInverse(structure.pinv,p,V.numRows);
        V.print();
        for (int i = 0; i < p.length; i++) {
            System.out.println(" [ "+p[i]+" ]");
        }
        CommonOps_DSCC.permutationMatrix(p,P.numRows,P);
        return P;
    }

    @Override
    public boolean isColumnPivot() {
        return false;
    }

    @Override
    public boolean isRowPivot() {
        return true;
    }

    @Override
    public DMatrixSparseCSC getQ(DMatrixSparseCSC Q, boolean compact) {

//        DMatrixSparseCSC I = CommonOps_DSCC.identity(V.numRows,V.numRows);
//        if( Q == null )
//            Q = new DMatrixSparseCSC(1,1,0);
//        Q.reshape(V.numRows,V.numRows,0);
//
//        for (int i = V.numCols-1; i >= 0; i--) {
//            QrHelperFunctions_DSCC.rank1UpdateMultR(V,i,beta[i],I,Q,gwork,gx);
//            I.set(Q);
//        }
//        return Q;

        DMatrixSparseCSC I = CommonOps_DSCC.identity(V.numRows,m);
        if( Q == null )
            Q = new DMatrixSparseCSC(1,1,0);
        Q.reshape(V.numRows,m,0);

        for (int i = V.numCols-1; i >= 0; i--) {
            QrHelperFunctions_DSCC.rank1UpdateMultR(V,i,beta[i],I,Q,gwork,gx);
            I.set(Q);
        }


        // Apply P transpose to Q
        CommonOps_DSCC.permutationInverse(structure.pinv,structureP,V.numRows);
        CommonOps_DSCC.permuteRowInv(structureP,Q,I);

//        for (int i = 0; i < V.numRows; i++) {
//            System.out.println(" p["+i+"] = "+structureP[i]+"   pinv["+i+"] = "+structure.pinv[i]);
//        }

        // Remove fictitious rows
        if( m2 > m )
            CommonOps_DSCC.extractRows(I,0,m-1,Q);
        else
            Q.set(I);

        return Q;
    }

    @Override
    public DMatrixSparseCSC getR(DMatrixSparseCSC R, boolean compact) {
        if( R == null )
            R = new DMatrixSparseCSC(0,0,0);

        R.set(this.R);
        if( m > n ) {
            // there should only be zeros past row m
            R.numRows = m;
        } else if( n > m && m2 != m ) {
            DMatrixSparseCSC tmp = new DMatrixSparseCSC(m,n,0);
            CommonOps_DSCC.extractRows(R,0,m-1,tmp);
            R.set(tmp);
        }
        return R;
    }

    @Override
    public boolean inputModified() {
        return false;
    }
}
