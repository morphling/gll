package gll.parser;

import cache.Cache2;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.Truffle;
import gll.grammar.Slot;
import gll.grammar.SortIdentifier;
import gll.gss.Frame;
import gll.gss.Initial;
import gll.gss.Link;
import gll.gss.Stack;
import gll.sppf.*;
import graph.dot.GraphBuilder;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.*;

/**
 * The state of a GLL parser.
 * 
 * @author Tillmann Rendel
 */
public class ParsingState implements State {
	/**
	 * The deque of currently active descriptors.
	 * 
	 * <p>
	 * This set contains all descriptors that have to be executed to fully
	 * process the current token.
	 * </p>
	 */
	public final Deque<CallTarget> active = new ArrayDeque<>();

	/**
	 * The set of already scheduled descriptors for the next token.
	 * 
	 * <p>
	 * This set is used to handle left recursion by ensuring that no descriptor
	 * is scheduled twice.
	 * </p>
	 */
	public final Set<Stack> deadLater = new HashSet<Stack>();

	/**
	 * The set of already scheduled descriptors for the current token.
	 * 
	 * <p>
	 * This set is used to handle left recursion by ensuring that no descriptor
	 * is scheduled twice.
	 * </p>
	 */
	public final Map<Slot, Set<Stack>> deadNow = new HashMap<Slot, Set<Stack>>();

	/**
	 * All stack frames created for the current token.
	 */
	public Map<Slot, Frame> frames = new HashMap<Slot, Frame>();

	/**
	 * The set of descriptors for the next token.
	 * 
	 * <p>
	 * This set is filled during processing of the current token.
	 * </p>
	 */
	public Set<CallTarget> future = new HashSet<>();

	/**
	 * The next position in the token stream.
	 */
	public Position next = BeforeInput.create();

	/**
	 * The set of stack frames that have been popped for the current token.
	 */
	public final Map<Frame, Set<SymbolDerivation<?, ?>>> popped = new HashMap<Frame, Set<SymbolDerivation<?, ?>>>();

	/**
	 * Our position in the token stream.
	 * 
	 * <p>
	 * 0 is the position just before the first token.
	 * </p>
	 */
	public int position = -1;

	/**
	 * The start symbol of the grammar we are parsing.
	 */
	public SortIdentifier start;

	/**
	 * The derivation associated with the current token.
	 */
	public TerminalSymbolDerivation tokenDerivation;

	/**
	 * The initial stack frame.
	 */
	private final Initial empty = new Initial();

	/**
	 * A cache to avoid recreating identical empty intermediate derivations.
	 */
	private IntermediateEmpty emptyDerivation = null;

	/**
	 * The first position in the token stream.
	 */
	private Position first;

	/**
	 * The set of all GSS stacks ever created (for debugging!)
	 */
	private final Set<Stack> gss = new HashSet<Stack>();

	/**
	 * A cache to avoid recreating identical derivations for symbols.
	 */
	private final Cache2<Slot, Position, IntermediateCons> intermediateCons = new Cache2<Slot, Position, IntermediateCons>() {
		@Override
		protected IntermediateCons compute(final Slot label, final Position first) {
			return new IntermediateCons(label, first, previous);
		}
	};

	/**
	 * A cache to avoid recreating identical derivations for nonterminal
	 * symbols.
	 */
	private final Cache2<SortIdentifier, Position, NonterminalSymbolDerivation> nonterminalSymbolDerivations = new Cache2<SortIdentifier, Position, NonterminalSymbolDerivation>() {
		/**
		 * {@inheritDoc}
		 */
		@Override
		protected NonterminalSymbolDerivation compute(final SortIdentifier sort, final Position first) {
			return new NonterminalSymbolDerivation(sort, first, previous);
		}
	};

	/**
	 * The previous position in the token stream.
	 */
	private Position previous = null;

	/**
	 * The current result of parsing.
	 */
	private NonterminalSymbolDerivation result;

    /**
	 * {@inheritDoc}
	 */
	@Override
	public IntermediateCons append(final Slot slot, final Intermediate<?> lhs, final SymbolDerivation<?, ?> rhs) {
		final Position first = lhs.getFirst();
		final Position middle = rhs.getFirst();

		final IntermediateCons result = intermediateCons.apply(slot, first);

		result.add(new Binary(slot, middle, lhs, rhs));

		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public IntermediateEmpty createEmpty() {
		return emptyDerivation;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public NonterminalSymbolDerivation createNonterminalSymbolDerivation(final SortIdentifier sort, final Position first,
			final Unary derivation) {
		final NonterminalSymbolDerivation result = nonterminalSymbolDerivations.apply(sort, first);
		result.add(derivation);
		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public TerminalSymbolDerivation createTokenDerivation() {
		return tokenDerivation;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public boolean deadNow(final Slot slot, final Stack frame) {
		Set<Stack> frames = deadNow.get(slot);
		if (frames == null) {
			frames = new HashSet<Stack>();
			deadNow.put(slot, frames);
		}

		if (frames.contains(frame)) {
			return true;
		} else {
			frames.add(frame);
			return false;
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Initial empty() {
		gss.add(empty);
		return empty;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public int getPosition() {
		return position;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public NonterminalSymbolDerivation getResult() {
		return result;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void markPopped(final Frame frame, final SymbolDerivation<?, ?> result) {
		Set<SymbolDerivation<?, ?>> results = popped.get(frame);
		if (results == null) {
			results = new HashSet<SymbolDerivation<?, ?>>();
		}
		results.add(result);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void nextToken(final int codepoint) {
		// reset caches that depend on current token position
		active.addAll(future);
		future.clear();

		deadNow.clear();
		deadLater.clear();

		frames.clear();
		popped.clear();
		intermediateCons.clear();
		nonterminalSymbolDerivations.clear();

		// increase token position
		position = position + 1;

		// create current symbol and related things
		previous = next;
		if (codepoint >= 0) {
			final InputSymbol symbol = new InputSymbol(codepoint, position);
			tokenDerivation = new TerminalSymbolDerivation(symbol);
			next = symbol;
		} else {
			next = new AfterInput(position);
			tokenDerivation = null;
		}
		if (position == 0) {
			first = next;
		}

		emptyDerivation = new IntermediateEmpty(next, previous);

		result = nonterminalSymbolDerivations.apply(start, first);
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public Stack push(final Slot slot, final Stack caller, final int token, final Intermediate<?> derivation) {
		Frame callee = frames.get(slot);
		if (callee == null) {
			callee = new Frame(slot, token);
			frames.put(slot, callee);
			gss.add(callee);
		}

		final Link link = callee.link(caller, derivation);

		final Set<SymbolDerivation<?, ?>> results = popped.get(callee);
		if (results != null) {
			for (final SymbolDerivation<?, ?> result : results) {
				link.schedule(this, result, slot);
			}
		}

		return callee;
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void scheduleLater(final Stack caller, final TerminalSymbolDerivation derivation) {
		if (!deadLater.contains(caller)) {
			deadLater.add(caller);
            future.add(Truffle.getRuntime().createCallTarget(new FutureProcessRootNode(caller, derivation)));
		}
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	public void scheduleNow(final Slot slot, final Stack caller, final Intermediate<?> derivation) {
        if (!deadNow(slot, caller)) {
            active.add(Truffle.getRuntime().createCallTarget(new SlotProcessRootNode(slot, caller, derivation)));
		}
	}

    /**
	 * {@inheritDoc}
	 */
	@Override
	public void writeGSS(final String file) {
		final GraphBuilder builder = new GraphBuilder();
		for (final Stack stack : gss) {
			builder.visit(stack);
		}
		try {
			builder.write(new PrintWriter(file));
		} catch (final FileNotFoundException e) {
			e.printStackTrace();
		}
	}
}
