/**
 * This code is released under the
 * Apache License Version 2.0 http://www.apache.org/licenses/.
 *
 * (c) Daniel Lemire, http://lemire.me/en/
 */
package me.lemire.integercompression;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * This is a patching scheme designed for speed.
 *  It encodes integers in blocks of 128 integers within pages of
 *  up to 65536 integers. Note that it is important, to get good
 *  compression and good performance, to use sizeable blocks (greater than 1024 integers).
 *  For arrays containing a number of integers that is not divisible by 128, you should use
 * it in conjunction with another CODEC: 
 * 
 *  IntegerCODEC ic = new Composition(new FastPFOR(), new VariableByte()).
 * <p>
 * For details, please see
 * </p><p>
 * Daniel Lemire and Leonid Boytsov, Decoding billions of integers per second
 * through vectorization Software: Practice &amp; Experience
 * <a href="http://onlinelibrary.wiley.com/doi/10.1002/spe.2203/abstract">http://onlinelibrary.wiley.com/doi/10.1002/spe.2203/abstract</a>
 * <a href="http://arxiv.org/abs/1209.2137">http://arxiv.org/abs/1209.2137</a>
 * </p>
 * <p>For sufficiently compressible and long arrays, it is faster and better than other PFOR
 * schemes.</p>
 * 
 * Note that this does not use differential coding: if you are working on sorted
 * lists, use IntegratedFastPFOR instead.
 * 
 * For multi-threaded applications, each thread should use its own FastPFOR
 * object.
 * 
 * @author Daniel Lemire
 */
public final class FastPFOR implements IntegerCODEC {
        final static int BLOCK_SIZE = 128;
        final static int OVERHEAD_OF_EACH_EXCEPT = 8;
        final static int DEFAULT_PAGE_SIZE = 65536;

        int pageSize;
        final int[][] dataTobePacked = new int[33][];
        final ByteBuffer byteContainer;

        // Working area for compress and uncompress.
        int[] dataPointers;
        int[] freqs;
        byte[] bestbbestcexceptmaxb;

        /**
         * Construct the FastPFOR CODEC.
         * 
         * @param pagesize
         *                the desired page size (for expert use)
         */
        public FastPFOR(int pagesize) {
                pageSize = pagesize;
                // Initiate arrrays.
                byteContainer = ByteBuffer.allocateDirect(3 * pageSize
                        / BLOCK_SIZE + pageSize);
                byteContainer.order(ByteOrder.LITTLE_ENDIAN);
                for (int k = 1; k < dataTobePacked.length; ++k)
                        dataTobePacked[k] = new int[pageSize / 32 * 4]; // heuristic
        }

        /**
         * Construct the fastPFOR CODEC with default parameters.
         */
        public FastPFOR() {
                this(DEFAULT_PAGE_SIZE);
        }

        /**
         * Compress data in blocks of 128 integers (if fewer than 128 integers
         * are provided, nothing is done).
         * 
         * @see IntegerCODEC#compress(int[], IntWrapper, int, int[], IntWrapper)
         */
        @Override
        public void compress(int[] in, IntWrapper inpos, int inlength,
                int[] out, IntWrapper outpos) {
                inlength = Util.floorBy(inlength, 128);
                if (inlength == 0)
                        return;

                out[outpos.get()] = inlength;
                outpos.increment();

                // Allocate memory for working area.
                dataPointers = new int[33];
                freqs = new int[33];
                bestbbestcexceptmaxb = new byte[3];

                final int finalinpos = inpos.get() + inlength;
                while (inpos.get() != finalinpos) {
                        int thissize = Math.min(pageSize,
                                finalinpos - inpos.get());
                        encodePage(in, inpos, thissize, out, outpos);
                }

                dataPointers = null;
                freqs = null;
                bestbbestcexceptmaxb = null;
        }

        private void getBestBFromData(int[] in, int pos) {
                Arrays.fill(freqs, 0);
                for (int k = pos, k_end = pos + BLOCK_SIZE; k < k_end; ++k) {
                        freqs[Util.bits(in[k])]++;
                }
                bestbbestcexceptmaxb[0] = 32;
                while (freqs[bestbbestcexceptmaxb[0]] == 0)
                        bestbbestcexceptmaxb[0]--;
                bestbbestcexceptmaxb[2] = bestbbestcexceptmaxb[0];
                int bestcost = bestbbestcexceptmaxb[0] * BLOCK_SIZE;
                byte cexcept = 0;
                bestbbestcexceptmaxb[1] = cexcept;
                for (int b = bestbbestcexceptmaxb[0] - 1; b >= 0; --b) {
                        cexcept += freqs[b + 1];
                        if (cexcept < 0)
                                break;
                        // the extra 8 is the cost of storing maxbits
                        int thiscost = cexcept * OVERHEAD_OF_EACH_EXCEPT
                                + cexcept * (bestbbestcexceptmaxb[2] - b) + b
                                * BLOCK_SIZE + 8;
                        if (thiscost < bestcost) {
                                bestcost = thiscost;
                                bestbbestcexceptmaxb[0] = (byte) b;
                                bestbbestcexceptmaxb[1] = cexcept;
                        }
                }
        }

        private void encodePage(int[] in, IntWrapper inpos, int thissize,
                int[] out, IntWrapper outpos) {
                final int headerpos = outpos.get();
                outpos.increment();
                int tmpoutpos = outpos.get();

                // Clear working area.
                Arrays.fill(dataPointers, 0);
                // Arrays.fill(bestbbestcexceptmaxb, (byte)0);//DL:unncessary
                byteContainer.clear();

                int tmpinpos = inpos.get();
                for (final int finalinpos = tmpinpos + thissize - BLOCK_SIZE; tmpinpos <= finalinpos; tmpinpos += BLOCK_SIZE) {
                        getBestBFromData(in, tmpinpos);
                        final int tmpbestb = bestbbestcexceptmaxb[0];
                        byteContainer.put(bestbbestcexceptmaxb[0]);
                        byteContainer.put(bestbbestcexceptmaxb[1]);
                        if (bestbbestcexceptmaxb[1] > 0) {
                                byteContainer.put(bestbbestcexceptmaxb[2]);
                                final int index = bestbbestcexceptmaxb[2]
                                        - bestbbestcexceptmaxb[0];
                                if (dataPointers[index]
                                        + bestbbestcexceptmaxb[1] >= dataTobePacked[index].length) {
                                        int newsize = 2 * (dataPointers[index] + bestbbestcexceptmaxb[1]);
                                        // make sure it is a multiple of 32
                                        newsize = Util
                                                .floorBy(newsize + 31, 32);
                                        dataTobePacked[index] = Arrays.copyOf(
                                                dataTobePacked[index], newsize);
                                }
                                for (int k = 0; k < BLOCK_SIZE; ++k) {
                                        if ((in[k + tmpinpos] >>> bestbbestcexceptmaxb[0]) != 0) {
                                                // we have an exception
                                                byteContainer.put((byte) k);
                                                dataTobePacked[index][dataPointers[index]++] = in[k
                                                        + tmpinpos] >>> tmpbestb;
                                        }
                                }

                        }
                        for (int k = 0; k < 128; k += 32) {
                                BitPacking.fastpack(in, tmpinpos + k, out,
                                        tmpoutpos, tmpbestb);
                                tmpoutpos += tmpbestb;
                        }
                }
                inpos.set(tmpinpos);
                out[headerpos] = tmpoutpos - headerpos;
                final int bytesize = byteContainer.position();
                while ((byteContainer.position() & 3) != 0)
                        byteContainer.put((byte) 0);
                out[tmpoutpos++] = bytesize;
                final int howmanyints = byteContainer.position() / 4;
                byteContainer.flip();
                byteContainer.asIntBuffer().get(out, tmpoutpos, howmanyints);
                tmpoutpos += howmanyints;
                int bitmap = 0;
                for (int k = 1; k <= 32; ++k) {
                        if (dataPointers[k] != 0)
                                bitmap |= (1 << (k - 1));
                }
                out[tmpoutpos++] = bitmap;
                for (int k = 1; k <= 32; ++k) {
                        if (dataPointers[k] != 0) {
                                out[tmpoutpos++] = dataPointers[k];// size
                                for (int j = 0; j < dataPointers[k]; j += 32) {
                                        BitPacking.fastpack(dataTobePacked[k],
                                                j, out, tmpoutpos, k);
                                        tmpoutpos += k;
                                }
                        }
                }
                outpos.set(tmpoutpos);
        }

        /**
         * Uncompress data in blocks of 128 integers. In this particular case,
         * the inlength parameter is ignored: it is deduced from the compressed
         * data.
         * 
         * @see IntegerCODEC#compress(int[], IntWrapper, int, int[], IntWrapper)
         */
        @Override
        public void uncompress(int[] in, IntWrapper inpos, int inlength,
                int[] out, IntWrapper outpos) {
                if (inlength == 0)
                        return;

                int mynvalue = in[inpos.get()];
                inpos.increment();

                dataPointers = new int[33];

                int finalout = outpos.get() + mynvalue;
                while (outpos.get() != finalout) {
                        int thissize = Math.min(pageSize,
                                finalout - outpos.get());
                        decodePage(in, inpos, out, outpos, thissize);
                }

                dataPointers = null;
        }

        private void decodePage(int[] in, IntWrapper inpos, int[] out,
                IntWrapper outpos, int thissize) {
                final int initpos = inpos.get();
                final int wheremeta = in[inpos.get()];
                inpos.increment();
                int inexcept = initpos + wheremeta;
                final int bytesize = in[inexcept++];
                byteContainer.clear();
                byteContainer.asIntBuffer().put(in, inexcept, (bytesize + 3) / 4);
                inexcept += (bytesize + 3)/ 4;

                final int bitmap = in[inexcept++];
                for (int k = 1; k <= 32; ++k) {
                        if ((bitmap & (1 << (k - 1))) != 0) {
                                int size = in[inexcept++];
                                if (dataTobePacked[k].length < size)
                                        dataTobePacked[k] = new int[Util
                                                .floorBy(size + 31, 32)];
                                for (int j = 0; j < size; j += 32) {
                                        BitPacking.fastunpack(in, inexcept,
                                                dataTobePacked[k], j, k);
                                        inexcept += k;
                                }
                        }
                }
                Arrays.fill(dataPointers, 0);
                int tmpoutpos = outpos.get();
                int tmpinpos = inpos.get();

                for (int run = 0, run_end = thissize / BLOCK_SIZE; run < run_end; ++run, tmpoutpos += BLOCK_SIZE) {
                        final byte b = byteContainer.get();
                        final byte cexcept = byteContainer.get();
                        for (int k = 0; k < 128; k += 32) {
                                BitPacking.fastunpack(in, tmpinpos, out,
                                        tmpoutpos + k, b);
                                tmpinpos += b;
                        }
                        if (cexcept > 0) {
                                final byte maxbits = byteContainer.get();
                                final int index = maxbits - b;
                                for (int k = 0; k < cexcept; ++k) {
                                        final byte pos = byteContainer.get();
                                        final int exceptvalue = dataTobePacked[index][dataPointers[index]++];
                                        out[pos + tmpoutpos] |= exceptvalue << b;
                                }

                        }
                }
                outpos.set(tmpoutpos);
                inpos.set(inexcept);
        }

        @Override
        public String toString() {
                return this.getClass().getSimpleName();
        }
}
